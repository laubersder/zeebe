/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.zeebe.dispatcher.Dispatcher;
import io.zeebe.dispatcher.Dispatchers;
import io.zeebe.util.actor.ActorScheduler;
import io.zeebe.util.actor.ActorSchedulerBuilder;

public class SingleMessageTest
{
    protected final TransportMessage clientMessage = new TransportMessage();
    protected final TransportMessage serverMessage = new TransportMessage();
    protected UnsafeBuffer messageBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(1024));

    private ActorScheduler actorScheduler;
    private ClientTransport clientTransport;
    private ServerTransport serverTransport;

    @Before
    public void setup()
    {
        actorScheduler = ActorSchedulerBuilder.createDefaultScheduler("test");
    }

    @After
    public void teardown() throws Exception
    {
        clientTransport.close();
        serverTransport.close();
        actorScheduler.close();
    }

    @Test
    public void shouldEchoMessages() throws Exception
    {
        final SocketAddress addr = new SocketAddress("localhost", 51115);
        final int numRequests = 1_000_000;

        final CountingListener responseCounter = new CountingListener();

        final Dispatcher clientSendBuffer = Dispatchers.create("clientSendBuffer")
                .bufferSize(32 * 1024 * 1024)
                .subscriptions("sender")
                .actorScheduler(actorScheduler)
                .build();

        final Dispatcher serverSendBuffer = Dispatchers.create("serverSendBuffer")
            .bufferSize(32 * 1024 * 1024)
            .subscriptions("sender")
            .actorScheduler(actorScheduler)
            .build();

        clientTransport = Transports.newClientTransport()
            .sendBuffer(clientSendBuffer)
            .requestPoolSize(128)
            .scheduler(actorScheduler)
            .inputListener(responseCounter)
            .build();

        serverTransport = Transports.newServerTransport()
            .sendBuffer(serverSendBuffer)
            .bindAddress(addr.toInetSocketAddress())
            .scheduler(actorScheduler)
            .build((output, remote, buf, offset, length) ->
            {
                serverMessage
                    .reset()
                    .buffer(buf, offset, length)
                    .remoteStreamId(remote.getStreamId());
                return output.sendMessage(serverMessage);
            }, null);

        final RemoteAddress remoteAddress = clientTransport.registerRemoteAddress(addr);

        for (int i = 0; i < numRequests; i++)
        {
            messageBuffer.putInt(0, i);
            clientMessage.reset()
                .buffer(messageBuffer)
                .remoteAddress(remoteAddress);

            while (!clientTransport.getOutput().sendMessage(clientMessage))
            {
                // spin
            }
        }

        while (responseCounter.numMessagesReceived < numRequests)
        {
        }

        assertThat(responseCounter.numMessagesReceived).isEqualTo(numRequests);
    }

    protected static class CountingListener implements ClientInputListener
    {

        protected volatile int numMessagesReceived = 0;

        @Override
        public void onResponse(int streamId, long requestId, DirectBuffer buffer, int offset, int length)
        {
        }

        @Override
        public void onMessage(int streamId, DirectBuffer buffer, int offset, int length)
        {
            numMessagesReceived++;
        }
    }

}
