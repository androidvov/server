package io.moquette.spi.impl;

import io.moquette.parser.proto.messages.AbstractMessage;
import io.moquette.parser.proto.messages.PublishMessage;
import io.moquette.server.ConnectionDescriptor;
import io.moquette.spi.ClientSession;
import io.moquette.spi.IMessagesStore;
import io.moquette.spi.ISessionsStore;
import io.moquette.spi.MessageGUID;
import io.moquette.spi.impl.subscriptions.Subscription;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

import static io.moquette.spi.impl.ProtocolProcessor.lowerQosToTheSubscriptionDesired;

class MetaPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(MetaPublisher.class);
    private final ConcurrentMap<String, ConnectionDescriptor> connectionDescriptors;
    private final ISessionsStore m_sessionsStore;
    private final IMessagesStore m_messagesStore;
    private final Qos0Publisher qos0Publisher;
    private final Qos2Publisher qos2Publisher;

    public MetaPublisher(ConcurrentMap<String, ConnectionDescriptor> connectionDescriptors, ISessionsStore sessionsStore,
                         IMessagesStore messagesStore) {
        this.connectionDescriptors = connectionDescriptors;
        this.m_sessionsStore = sessionsStore;
        this.m_messagesStore = messagesStore;
        this.qos0Publisher = new Qos0Publisher(connectionDescriptors, sessionsStore);
        this.qos2Publisher = new Qos2Publisher(connectionDescriptors, sessionsStore, m_messagesStore);
    }

    void publish2Subscribers(IMessagesStore.StoredMessage pubMsg, List<Subscription> topicMatchingSubscriptions) {
        final String topic = pubMsg.getTopic();
        final AbstractMessage.QOSType publishingQos = pubMsg.getQos();
        final ByteBuffer origMessage = pubMsg.getMessage();

        //if QoS 1 or 2 store the message
        MessageGUID guid = null;
        if (publishingQos != AbstractMessage.QOSType.MOST_ONE) {
            guid = m_messagesStore.storePublishForFuture(pubMsg);
        }

        LOG.trace("Found {} matching subscriptions to <{}>", topicMatchingSubscriptions.size(), topic);
        for (final Subscription sub : topicMatchingSubscriptions) {
            AbstractMessage.QOSType qos = lowerQosToTheSubscriptionDesired(sub, publishingQos);
            ClientSession targetSession = m_sessionsStore.sessionForClient(sub.getClientId());

            boolean targetIsActive = this.connectionDescriptors.containsKey(sub.getClientId());

            LOG.debug("Broker republishing to client <{}> topic <{}> qos <{}>, active {}",
                    sub.getClientId(), sub.getTopicFilter(), qos, targetIsActive);
            ByteBuffer message = origMessage.duplicate();
            if (targetIsActive) {
                //QoS 0
                if (qos == AbstractMessage.QOSType.MOST_ONE) {
                    this.qos0Publisher.publishQos0(targetSession, topic, message);
                } else {
                    //QoS 1 or 2
                    int messageId = targetSession.nextPacketId();
                    targetSession.inFlightAckWaiting(guid, messageId);
                    this.qos2Publisher.publishQos2(targetSession, topic, qos, message, false, messageId);
                }
            } else {
                if (!targetSession.isCleanSession()) {
                    //store the message in targetSession queue to deliver
                    targetSession.enqueueToDeliver(guid);
                }
            }
        }
    }
}
