package org.camunda.tngp.client.task.impl;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.camunda.tngp.client.event.impl.EventSubscription;

public class EventSubscriptions<T extends EventSubscription<T>>
{
    // topicId => subscriptionId => subscription (subscription ids are not guaranteed to be globally unique)
    protected Int2ObjectHashMap<Long2ObjectHashMap<T>> subscriptions = new Int2ObjectHashMap<>();

    protected final List<T> pollableSubscriptions = new CopyOnWriteArrayList<>();
    protected final List<T> managedSubscriptions = new CopyOnWriteArrayList<>();

    protected void addPollableSubscription(T subscription)
    {
        this.subscriptions
            .computeIfAbsent(subscription.getTopicId(), (i) -> new Long2ObjectHashMap<>())
            .put(subscription.getId(), subscription);
        this.pollableSubscriptions.add(subscription);
    }

    protected void addManagedSubscription(T subscription)
    {
        this.subscriptions
            .computeIfAbsent(subscription.getTopicId(), (i) -> new Long2ObjectHashMap<>())
            .put(subscription.getId(), subscription);
        this.managedSubscriptions.add(subscription);
    }

    public void addSubscription(T subscription)
    {
        if (subscription.isManagedSubscription())
        {
            addManagedSubscription(subscription);
        }
        else
        {
            addPollableSubscription(subscription);
        }
    }

    public void closeAll()
    {
        for (T subscription : pollableSubscriptions)
        {
            subscription.close();
        }

        for (T subscription : managedSubscriptions)
        {
            subscription.close();
        }
    }

    public List<T> getManagedSubscriptions()
    {
        return managedSubscriptions;
    }

    public List<T> getPollableSubscriptions()
    {
        return pollableSubscriptions;
    }

    public void removeSubscription(T subscription)
    {
        final Long2ObjectHashMap<T> subscriptionsForTopic = subscriptions.get(subscription.getTopicId());
        if (subscriptionsForTopic != null)
        {
            subscriptionsForTopic.remove(subscription.getId());

            if (subscriptionsForTopic.isEmpty())
            {
                subscriptions.remove(subscription.getTopicId());
            }
        }

        pollableSubscriptions.remove(subscription);
        managedSubscriptions.remove(subscription);
    }

    public T getSubscription(int topicId, long id)
    {
        final Long2ObjectHashMap<T> subscriptionsForTopic = subscriptions.get(topicId);
        if (subscriptionsForTopic != null)
        {
            return subscriptionsForTopic.get(id);
        }
        else
        {
            return null;
        }
    }


}
