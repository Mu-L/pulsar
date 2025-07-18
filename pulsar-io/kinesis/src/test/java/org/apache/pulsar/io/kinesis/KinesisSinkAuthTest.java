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
package org.apache.pulsar.io.kinesis;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicSessionCredentials;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.Map;
import org.apache.pulsar.io.aws.AwsCredentialProviderPlugin;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.collections.Maps;

public class KinesisSinkAuthTest {

    @Test
    public void testDefaultCredentialProvider() throws Exception {
        KinesisSink sink = new KinesisSink();
        Map<String, String> credentialParam = Maps.newHashMap();
        String awsCredentialPluginParam = new Gson().toJson(credentialParam);
        try {
            sink.defaultCredentialProvider(awsCredentialPluginParam);
            Assert.fail("accessKey and SecretKey validation not applied");
        } catch (IllegalArgumentException ie) {
            // Ok..
        }

        final String accesKey = "ak";
        final String secretKey = "sk";
        credentialParam.put(KinesisSink.ACCESS_KEY_NAME, accesKey);
        credentialParam.put(KinesisSink.SECRET_KEY_NAME, secretKey);
        awsCredentialPluginParam = new Gson().toJson(credentialParam);
        AWSCredentialsProvider credentialProvider = sink.defaultCredentialProvider(awsCredentialPluginParam)
                .getCredentialProvider();
        Assert.assertNotNull(credentialProvider);
        Assert.assertEquals(credentialProvider.getCredentials().getAWSAccessKeyId(), accesKey);
        Assert.assertEquals(credentialProvider.getCredentials().getAWSSecretKey(), secretKey);

        sink.close();
    }

    @Test
    public void testCredentialProvider() throws Exception {
        KinesisSink sink = new KinesisSink();

        final String accesKey = "ak";
        final String secretKey = "sk";
        Map<String, String> credentialParam = Maps.newHashMap();
        credentialParam.put(KinesisSink.ACCESS_KEY_NAME, accesKey);
        credentialParam.put(KinesisSink.SECRET_KEY_NAME, secretKey);
        String awsCredentialPluginParam = new Gson().toJson(credentialParam);
        AWSCredentialsProvider credentialProvider = sink.createCredentialProvider(null, awsCredentialPluginParam)
                .getCredentialProvider();
        Assert.assertEquals(credentialProvider.getCredentials().getAWSAccessKeyId(), accesKey);
        Assert.assertEquals(credentialProvider.getCredentials().getAWSSecretKey(), secretKey);

        credentialProvider = sink.createCredentialProvider(AwsCredentialProviderPluginImpl.class.getName(), "{}")
                .getCredentialProvider();
        Assert.assertNotNull(credentialProvider);
        Assert.assertEquals(credentialProvider.getCredentials().getAWSAccessKeyId(),
                AwsCredentialProviderPluginImpl.ACCESS_KEY);
        Assert.assertEquals(credentialProvider.getCredentials().getAWSSecretKey(),
                AwsCredentialProviderPluginImpl.SECRET_KEY);
        Assert.assertEquals(((BasicSessionCredentials) credentialProvider.getCredentials()).getSessionToken(),
                AwsCredentialProviderPluginImpl.SESSION_TOKEN);

        sink.close();
    }

    @Test
    public void testCredentialProviderPlugin() throws Exception {
        KinesisSink sink = new KinesisSink();

        AWSCredentialsProvider credentialProvider = sink
                .createCredentialProviderWithPlugin(AwsCredentialProviderPluginImpl.class.getName(), "{}")
                .getCredentialProvider();
        Assert.assertNotNull(credentialProvider);
        Assert.assertEquals(credentialProvider.getCredentials().getAWSAccessKeyId(),
                AwsCredentialProviderPluginImpl.ACCESS_KEY);
        Assert.assertEquals(credentialProvider.getCredentials().getAWSSecretKey(),
                AwsCredentialProviderPluginImpl.SECRET_KEY);
        Assert.assertEquals(((BasicSessionCredentials) credentialProvider.getCredentials()).getSessionToken(),
                AwsCredentialProviderPluginImpl.SESSION_TOKEN);

        sink.close();
    }

    public static class AwsCredentialProviderPluginImpl implements AwsCredentialProviderPlugin {

        public static final String ACCESS_KEY = "ak";
        public static final String SECRET_KEY = "sk";
        public static final String SESSION_TOKEN = "st";

        public void init(String param) {
            // no-op
        }

        @Override
        public AWSCredentialsProvider getCredentialProvider() {
            return new AWSCredentialsProvider() {
                @Override
                public AWSCredentials getCredentials() {
                    return new BasicSessionCredentials(ACCESS_KEY, SECRET_KEY, SESSION_TOKEN) {

                        @Override
                        public String getAWSAccessKeyId() {
                            return ACCESS_KEY;
                        }
                        @Override
                        public String getAWSSecretKey() {
                            return SECRET_KEY;
                        }
                        @Override
                        public String getSessionToken() {
                            return SESSION_TOKEN;
                        }
                    };
                }
                @Override
                public void refresh() {
                    // TODO Auto-generated method stub
                }
            };
        }
        @Override
        public void close() throws IOException {
            // TODO Auto-generated method stub
        }
    }

}
