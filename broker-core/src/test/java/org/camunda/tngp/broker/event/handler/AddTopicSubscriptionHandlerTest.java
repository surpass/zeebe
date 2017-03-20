package org.camunda.tngp.broker.event.handler;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.camunda.tngp.broker.event.processor.TopicSubscription;
import org.camunda.tngp.broker.event.processor.TopicSubscriptionService;
import org.camunda.tngp.broker.logstreams.BrokerEventMetadata;
import org.camunda.tngp.broker.transport.clientapi.ErrorResponseWriter;
import org.camunda.tngp.broker.transport.controlmessage.ControlMessageResponseWriter;
import org.camunda.tngp.broker.util.msgpack.UnpackedObject;
import org.camunda.tngp.protocol.clientapi.ErrorCode;
import org.camunda.tngp.test.util.FluentMock;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class AddTopicSubscriptionHandlerTest
{

    protected FuturePool futurePool;

    @Mock
    protected TopicSubscriptionService subscriptionService;

    @FluentMock
    protected ControlMessageResponseWriter responseWriter;

    @FluentMock
    protected ErrorResponseWriter errorWriter;

    @Before
    public void setUp()
    {
        MockitoAnnotations.initMocks(this);
        futurePool = new FuturePool();
        when(subscriptionService.createSubscriptionAsync(any())).thenAnswer((invocation) -> futurePool.next());
    }

    @Test
    public void shouldWriteErrorOnFailure()
    {
        // given
        final AddTopicSubscriptionHandler handler = new AddTopicSubscriptionHandler(
                subscriptionService,
                responseWriter,
                errorWriter);

        final BrokerEventMetadata metadata = new BrokerEventMetadata();
        metadata.reqChannelId(14);

        final DirectBuffer request = encode(new TopicSubscription().setTopicId(3).setName("sub"));
        handler.handle(request, metadata);

        // when
        futurePool.at(0).completeExceptionally(new RuntimeException("foo"));

        // then
        verify(errorWriter).metadata(metadata);
        verify(errorWriter).failedRequest(request, 0, request.capacity());
        verify(errorWriter).errorCode(ErrorCode.REQUEST_PROCESSING_FAILURE);
        verify(errorWriter).tryWriteResponseOrLogFailure();
        verify(responseWriter, never()).tryWriteResponse();

    }

    protected static final DirectBuffer encode(UnpackedObject obj)
    {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[obj.getLength()]);
        obj.write(buffer, 0);
        return buffer;
    }
}
