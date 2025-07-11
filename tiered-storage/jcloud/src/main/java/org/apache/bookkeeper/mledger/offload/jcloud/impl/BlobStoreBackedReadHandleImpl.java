/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.bookkeeper.mledger.offload.jcloud.impl;

import com.google.common.annotations.VisibleForTesting;
import io.netty.buffer.ByteBuf;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.api.LastConfirmedAndEntry;
import org.apache.bookkeeper.client.api.LedgerEntries;
import org.apache.bookkeeper.client.api.LedgerEntry;
import org.apache.bookkeeper.client.api.LedgerMetadata;
import org.apache.bookkeeper.client.api.ReadHandle;
import org.apache.bookkeeper.client.impl.LedgerEntriesImpl;
import org.apache.bookkeeper.client.impl.LedgerEntryImpl;
import org.apache.bookkeeper.mledger.LedgerOffloaderStats;
import org.apache.bookkeeper.mledger.ManagedLedgerException;
import org.apache.bookkeeper.mledger.OffloadedLedgerHandle;
import org.apache.bookkeeper.mledger.offload.jcloud.BackedInputStream;
import org.apache.bookkeeper.mledger.offload.jcloud.OffloadIndexBlock;
import org.apache.bookkeeper.mledger.offload.jcloud.OffloadIndexBlockBuilder;
import org.apache.bookkeeper.mledger.offload.jcloud.impl.DataBlockUtils.VersionCheck;
import org.apache.pulsar.common.allocator.PulsarByteBufAllocator;
import org.apache.pulsar.common.naming.TopicName;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.KeyNotFoundException;
import org.jclouds.blobstore.domain.Blob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobStoreBackedReadHandleImpl implements ReadHandle, OffloadedLedgerHandle {
    private static final Logger log = LoggerFactory.getLogger(BlobStoreBackedReadHandleImpl.class);

    protected static final AtomicIntegerFieldUpdater<BlobStoreBackedReadHandleImpl> PENDING_READ_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(BlobStoreBackedReadHandleImpl.class, "pendingRead");

    private final long ledgerId;
    private final OffloadIndexBlock index;
    private final BackedInputStream inputStream;
    private final DataInputStream dataStream;
    private final ExecutorService executor;
    private final OffsetsCache entryOffsetsCache;
    private final AtomicReference<CompletableFuture<Void>> closeFuture = new AtomicReference<>();

    enum State {
        Opened,
        Closed
    }

    private volatile State state = null;

    private volatile int pendingRead;

    private volatile long lastAccessTimestamp = System.currentTimeMillis();

    private BlobStoreBackedReadHandleImpl(long ledgerId, OffloadIndexBlock index,
                                          BackedInputStream inputStream, ExecutorService executor,
                                          OffsetsCache entryOffsetsCache) {
        this.ledgerId = ledgerId;
        this.index = index;
        this.inputStream = inputStream;
        this.dataStream = new DataInputStream(inputStream);
        this.executor = executor;
        this.entryOffsetsCache = entryOffsetsCache;
        state = State.Opened;
    }

    @Override
    public long getId() {
        return ledgerId;
    }

    @Override
    public LedgerMetadata getLedgerMetadata() {
        return index.getLedgerMetadata();
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        if (closeFuture.get() != null || !closeFuture.compareAndSet(null, new CompletableFuture<>())) {
            return closeFuture.get();
        }

        CompletableFuture<Void> promise = closeFuture.get();
        executor.execute(() -> {
            try {
                index.close();
                inputStream.close();
                state = State.Closed;
                promise.complete(null);
            } catch (IOException t) {
                promise.completeExceptionally(t);
            }
        });
        return promise;
    }

    @Override
    public CompletableFuture<LedgerEntries> readAsync(long firstEntry, long lastEntry) {
        if (log.isDebugEnabled()) {
            log.debug("Ledger {}: reading {} - {} ({} entries}",
                    getId(), firstEntry, lastEntry, (1 + lastEntry - firstEntry));
        }
        CompletableFuture<LedgerEntries> promise = new CompletableFuture<>();

        // Ledger handles will be only marked idle when "pendingRead" is "0", it is not needed to update
        // "lastAccessTimestamp" if "pendingRead" is larger than "0".
        // Rather than update "lastAccessTimestamp" when starts a reading, updating it when a reading task is finished
        // is better.
        PENDING_READ_UPDATER.incrementAndGet(this);
        promise.whenComplete((__, ex) -> {
            lastAccessTimestamp = System.currentTimeMillis();
            PENDING_READ_UPDATER.decrementAndGet(BlobStoreBackedReadHandleImpl.this);
        });
        executor.execute(() -> {
            if (state == State.Closed) {
                log.warn("Reading a closed read handler. Ledger ID: {}, Read range: {}-{}",
                        ledgerId, firstEntry, lastEntry);
                promise.completeExceptionally(new ManagedLedgerException.OffloadReadHandleClosedException());
                return;
            }

            List<LedgerEntry> entries = new ArrayList<LedgerEntry>();
            boolean seeked = false;
            try {
                if (firstEntry > lastEntry
                    || firstEntry < 0
                    || lastEntry > getLastAddConfirmed()) {
                    promise.completeExceptionally(new BKException.BKIncorrectParameterException());
                    return;
                }
                long entriesToRead = (lastEntry - firstEntry) + 1;
                long nextExpectedId = firstEntry;

                // checking the data stream has enough data to read to avoid throw EOF exception when reading data.
                // 12 bytes represent the stream have the length and entryID to read.
                if (dataStream.available() < 12) {
                    log.warn("There hasn't enough data to read, current available data has {} bytes,"
                        + " seek to the first entry {} to avoid EOF exception", inputStream.available(), firstEntry);
                    seekToEntry(firstEntry);
                }

                while (entriesToRead > 0) {
                    long currentPosition = inputStream.getCurrentPosition();
                    int length = dataStream.readInt();
                    if (length < 0) { // hit padding or new block
                        seekToEntry(nextExpectedId);
                        continue;
                    }
                    long entryId = dataStream.readLong();

                    if (entryId == nextExpectedId) {
                        entryOffsetsCache.put(ledgerId, entryId, currentPosition);
                        ByteBuf buf = PulsarByteBufAllocator.DEFAULT.buffer(length, length);
                        entries.add(LedgerEntryImpl.create(ledgerId, entryId, length, buf));
                        int toWrite = length;
                        while (toWrite > 0) {
                            toWrite -= buf.writeBytes(dataStream, toWrite);
                        }
                        entriesToRead--;
                        nextExpectedId++;
                    } else if (entryId > nextExpectedId && entryId < lastEntry) {
                        log.warn("The read entry {} is not the expected entry {} but in the range of {} - {},"
                            + " seeking to the right position", entryId, nextExpectedId, nextExpectedId, lastEntry);
                        seekToEntry(nextExpectedId);
                    } else if (entryId < nextExpectedId
                        && !index.getIndexEntryForEntry(nextExpectedId).equals(index.getIndexEntryForEntry(entryId))) {
                        log.warn("Read an unexpected entry id {} which is smaller than the next expected entry id {}"
                        + ", seeking to the right position", entryId, nextExpectedId);
                        seekToEntry(nextExpectedId);
                    } else if (entryId > lastEntry) {
                        // in the normal case, the entry id should increment in order. But if there has random access in
                        // the read method, we should allow to seek to the right position and the entry id should
                        // never over to the last entry again.
                        if (!seeked) {
                            seekToEntry(nextExpectedId);
                            seeked = true;
                            continue;
                        }
                        log.info("Expected to read {}, but read {}, which is greater than last entry {}",
                            nextExpectedId, entryId, lastEntry);
                        throw new BKException.BKUnexpectedConditionException();
                    } else {
                        long ignore = inputStream.skip(length);
                    }
                }

                promise.complete(LedgerEntriesImpl.create(entries));
            } catch (Throwable t) {
                log.error("Failed to read entries {} - {} from the offloader in ledger {}",
                    firstEntry, lastEntry, ledgerId, t);
                if (t instanceof KeyNotFoundException) {
                    promise.completeExceptionally(new BKException.BKNoSuchLedgerExistsException());
                } else {
                    promise.completeExceptionally(t);
                }
                entries.forEach(LedgerEntry::close);
            }
        });
        return promise;
    }

    private void seekToEntry(long nextExpectedId) throws IOException {
        Long knownOffset = entryOffsetsCache.getIfPresent(ledgerId, nextExpectedId);
        if (knownOffset != null) {
            inputStream.seek(knownOffset);
        } else {
            // we don't know the exact position
            // we seek to somewhere before the entry
            long dataOffset = index.getIndexEntryForEntry(nextExpectedId).getDataOffset();
            inputStream.seek(dataOffset);
        }
    }

    @Override
    public CompletableFuture<LedgerEntries> readUnconfirmedAsync(long firstEntry, long lastEntry) {
        return readAsync(firstEntry, lastEntry);
    }

    @Override
    public CompletableFuture<Long> readLastAddConfirmedAsync() {
        return CompletableFuture.completedFuture(getLastAddConfirmed());
    }

    @Override
    public CompletableFuture<Long> tryReadLastAddConfirmedAsync() {
        return CompletableFuture.completedFuture(getLastAddConfirmed());
    }

    @Override
    public long getLastAddConfirmed() {
        return getLedgerMetadata().getLastEntryId();
    }

    @Override
    public long getLength() {
        return getLedgerMetadata().getLength();
    }

    @Override
    public boolean isClosed() {
        return getLedgerMetadata().isClosed();
    }

    @Override
    public CompletableFuture<LastConfirmedAndEntry> readLastAddConfirmedAndEntryAsync(long entryId,
                                                                                      long timeOutInMillis,
                                                                                      boolean parallel) {
        CompletableFuture<LastConfirmedAndEntry> promise = new CompletableFuture<>();
        promise.completeExceptionally(new UnsupportedOperationException());
        return promise;
    }

    public static ReadHandle open(ScheduledExecutorService executor,
                                  BlobStore blobStore, String bucket, String key, String indexKey,
                                  VersionCheck versionCheck,
                                  long ledgerId, int readBufferSize,
                                  LedgerOffloaderStats offloaderStats, String managedLedgerName,
                                  OffsetsCache entryOffsetsCache)
            throws IOException, BKException.BKNoSuchLedgerExistsException {
        int retryCount = 3;
        OffloadIndexBlock index = null;
        IOException lastException = null;
        String topicName = TopicName.fromPersistenceNamingEncoding(managedLedgerName);
        // The following retry is used to avoid to some network issue cause read index file failure.
        // If it can not recovery in the retry, we will throw the exception and the dispatcher will schedule to
        // next read.
        // If we use a backoff to control the retry, it will introduce a concurrent operation.
        // We don't want to make it complicated, because in the most of case it shouldn't in the retry loop.
        while (retryCount-- > 0) {
            long readIndexStartTime = System.nanoTime();
            Blob blob = blobStore.getBlob(bucket, indexKey);
            if (blob == null) {
                log.error("{} not found in container {}", indexKey, bucket);
                throw new BKException.BKNoSuchLedgerExistsException();
            }
            offloaderStats.recordReadOffloadIndexLatency(topicName,
                    System.nanoTime() - readIndexStartTime, TimeUnit.NANOSECONDS);
            versionCheck.check(indexKey, blob);
            OffloadIndexBlockBuilder indexBuilder = OffloadIndexBlockBuilder.create();
            try (InputStream payLoadStream = blob.getPayload().openStream()) {
                index = (OffloadIndexBlock) indexBuilder.fromStream(payLoadStream);
            } catch (IOException e) {
                // retry to avoid the network issue caused read failure
                log.warn("Failed to get index block from the offoaded index file {}, still have {} times to retry",
                    indexKey, retryCount, e);
                lastException = e;
                continue;
            }
            lastException = null;
            break;
        }
        if (lastException != null) {
            throw lastException;
        }

        BackedInputStream inputStream = new BlobStoreBackedInputStreamImpl(blobStore, bucket, key,
                versionCheck, index.getDataObjectLength(), readBufferSize, offloaderStats, managedLedgerName);

        return new BlobStoreBackedReadHandleImpl(ledgerId, index, inputStream, executor, entryOffsetsCache);
    }

    // for testing
    @VisibleForTesting
    State getState() {
        return this.state;
    }

    @Override
    public long lastAccessTimestamp() {
        return lastAccessTimestamp;
    }

    @Override
    public int getPendingRead() {
        return PENDING_READ_UPDATER.get(this);
    }
}
