package com.avoscloud.leanchatlib.controller;

import android.content.Context;
import android.util.Log;

import com.avos.avoscloud.AVException;
import com.avos.avoscloud.im.v2.AVIMClient;
import com.avos.avoscloud.im.v2.AVIMClientEventHandler;
import com.avos.avoscloud.im.v2.AVIMConversation;
import com.avos.avoscloud.im.v2.AVIMConversationEventHandler;
import com.avos.avoscloud.im.v2.AVIMConversationQuery;
import com.avos.avoscloud.im.v2.AVIMException;
import com.avos.avoscloud.im.v2.AVIMMessage;
import com.avos.avoscloud.im.v2.AVIMMessageManager;
import com.avos.avoscloud.im.v2.AVIMTypedMessage;
import com.avos.avoscloud.im.v2.AVIMTypedMessageHandler;
import com.avos.avoscloud.im.v2.callback.AVIMClientCallback;
import com.avos.avoscloud.im.v2.callback.AVIMConversationCreatedCallback;
import com.avos.avoscloud.im.v2.callback.AVIMConversationQueryCallback;
import com.avos.avoscloud.im.v2.callback.AVIMMessagesQueryCallback;
import com.avoscloud.leanchatlib.activity.ChatActivity;
import com.avoscloud.leanchatlib.model.ConversationType;
import com.avoscloud.leanchatlib.model.MessageEvent;
import com.avoscloud.leanchatlib.model.Room;
import com.avoscloud.leanchatlib.utils.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import de.greenrobot.event.EventBus;

/**
 * Created by lzw on 15/2/10.
 */
public class ChatManager extends AVIMClientEventHandler {
    public static final String KEY_UPDATED_AT = "updatedAt";
    public static final String LOGTAG = "leanchatlib";
    private static ChatManager chatManager;

    private static Context context;

    public volatile static ArrayList<AVIMTypedMessage> messagesList = new ArrayList<>();

    private static ConnectionListener defaultConnectListener = new ConnectionListener() {
        @Override
        public void onConnectionChanged(boolean connect) {
            if (ChatManager.isDebugEnabled()) {
                Utils.log();
            }
        }
    };

    private ConnectionListener connectionListener = defaultConnectListener;
    private static boolean setupDatabase = false;
    private Map<String, AVIMConversation> cachedConversations = new HashMap<>();
    private AVIMClient imClient;
    private String selfId;
    private boolean connect = false;
    private MessageHandler messageHandler;
    private RoomsTable roomsTable;
    private EventBus eventBus = EventBus.getDefault();
    private ChatManagerAdapter chatManagerAdapter;
    private static boolean debugEnabled;
    private Map<String, AVIMTypedMessage> latestMessages = new HashMap<>();

    private ChatManager() {
    }

    public static synchronized ChatManager getInstance() {
        if (chatManager == null) {
            chatManager = new ChatManager();
        }
        return chatManager;
    }

    public static Context getContext() {
        return context;
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void setDebugEnabled(boolean debugEnabled) {
        ChatManager.debugEnabled = debugEnabled;
    }

    private OnConversation onConversation;

    public interface OnConversation {
        void onResult(List<AVIMConversation> list);
    }

    public void setOnConversation(OnConversation onConversation) {
        this.onConversation = onConversation;
    }

    public void fetchConversationById(final List consations, final String uid) {

        if (imClient == null) {
            imClient = AVIMClient.getInstance(uid);
        }

        AVIMConversationQuery query = imClient.getQuery();
        query.whereContainsIn("objectId", consations);
        query.findInBackground(new AVIMConversationQueryCallback() {
            @Override
            public void done(List<AVIMConversation> list, AVIMException e) {
                if (e == null) {
                    onConversation.onResult(list);
                } else {
                    Log.d("wz", "报异常：" + e.toString());
                }
            }
        });
    }
    public void fetchConversationById(List consations) {

        AVIMConversationQuery query = imClient.getQuery();
        query.whereContainsIn("objectId", consations);
        query.findInBackground(new AVIMConversationQueryCallback() {
            @Override
            public void done(List<AVIMConversation> list, AVIMException e) {
                if (e == null) {
                    onConversation.onResult(list);
                } else {
                    Log.d("wz", "报异常：" + e.toString());
                }
            }
        });
    }


    // fetchConversation
    public void fetchConversationWithUserId(String userId, final Map attrs, final AVIMConversationCreatedCallback callback) {
        final List<String> members = new ArrayList<>();
        members.add(userId);
        members.add(selfId);
        AVIMConversationQuery query = imClient.getQuery();
        query.withMembers(members);
        query.whereEqualTo(ConversationType.ATTR_TYPE_KEY, ConversationType.Single.getValue());
        query.orderByDescending(KEY_UPDATED_AT);
        query.findInBackground(new AVIMConversationQueryCallback() {
            @Override
            public void done(List<AVIMConversation> conversations, AVIMException e) {
                if (e != null) {
                    callback.done(null, e);
                } else {
                    if (attrs != null) {
                        attrs.put(ConversationType.TYPE_KEY, ConversationType.Single.getValue());
                        imClient.createConversation(members, null, attrs, false, true, callback);
                    } else {
                        Map<String, Object> attrs = new HashMap<>();
                        attrs.put(ConversationType.TYPE_KEY, ConversationType.Single.getValue());
                        imClient.createConversation(members, null, attrs, false, true, callback);
                    }
                }
            }

        });
    }


    public void init(Context context) {
        this.context = context;
        messageHandler = new MessageHandler();
        AVIMMessageManager.registerMessageHandler(AVIMTypedMessage.class, messageHandler);
//    try {
//      AVIMMessageManager.registerAVIMMessageType(AVIMUserInfoMessage.class);
//    } catch (AVException e) {
//      e.printStackTrace();
//    }
        AVIMClient.setClientEventHandler(this);
        //签名
        //AVIMClient.setSignatureFactory(new SignatureFactory());
    }

    public void setConversationEventHandler(AVIMConversationEventHandler eventHandler) {
        AVIMMessageManager.setConversationEventHandler(eventHandler);
    }

    public void setupDatabaseWithSelfId(String selfId) {
        this.selfId = selfId;
        if (setupDatabase) {
            return;
        }
        setupDatabase = true;
        roomsTable = RoomsTable.getCurrentUserInstance();
    }

    public void setConnectionListener(ConnectionListener connectionListener) {
        this.connectionListener = connectionListener;
    }

    public AVIMClient getImClient() {
        return imClient;
    }

    public String getSelfId() {
        return selfId;
    }

    public void openClientWithSelfId(String selfId, final AVIMClientCallback callback) {
        if (this.selfId == null) {
            throw new IllegalStateException("please call setupDatabaseWithSelfId() first");
        }
        if (!this.selfId.equals(selfId)) {
            throw new IllegalStateException("setupDatabaseWithSelfId and openClient's selfId should be equal");
        }
        imClient = AVIMClient.getInstance(selfId);
        imClient.open(new AVIMClientCallback() {

            @Override
            public void done(AVIMClient client, AVIMException e) {
                if (e != null) {
                    connect = false;
                    connectionListener.onConnectionChanged(connect);
                } else {
                    connect = true;
                    connectionListener.onConnectionChanged(connect);
                }
                if (callback != null) {
                    callback.done(client, e);
                }
            }
        });
    }

    private void onMessageReceipt(AVIMTypedMessage message, AVIMConversation conv, AVIMClient client) {
        if (message.getMessageId() == null) {
            throw new NullPointerException("message id is null");
        }
        onmessagecallbackReceipt1.onMessageReceipt(message, conv, client);
        MessageEvent messageEvent = new MessageEvent(message, MessageEvent.Type.Receipt);
        eventBus.post(messageEvent);
    }

    public void onMessage(final AVIMTypedMessage message, final AVIMConversation conversation, AVIMClient client) {
        Utils.log("receive message=" + message.getContent());

        if (message.getMessageId() == null) {
            //throw new NullPointerException("message id is null");
        }
        if (!ConversationHelper.isValidConversation(conversation)) {
            //throw new IllegalStateException("receive msg from invalid conversation");
        }
        if (lookUpConversationById(conversation.getConversationId()) == null) {
            registerConversation(conversation);
        }
        onmessagecallback1.onMessage(message, conversation, client);
        roomsTable.insertRoom(message.getConversationId());
        roomsTable.increaseUnreadCount(message.getConversationId());
        insertLatestMessage(message);
        MessageEvent messageEvent = new MessageEvent(message, MessageEvent.Type.Come);
        eventBus.post(messageEvent);
        if (selfId != null && ChatActivity.getCurrentChattingConvid() == null || !ChatActivity.getCurrentChattingConvid().equals(message
                .getConversationId())) {
            chatManagerAdapter.shouldShowNotification(context, selfId, conversation, message);
        }
    }

    /**
     * 自定义回调方法
     */
    public static interface onMessageCallBack {

        void onMessage(AVIMTypedMessage message, AVIMConversation conversation, AVIMClient client);
    }

    public static onMessageCallBack onmessagecallback1;

    public static void getMessage(onMessageCallBack onmessagecallback) {
        onmessagecallback1 = onmessagecallback;
    }


    public static interface onMessageReceiptCallBack {

        void onMessageReceipt(AVIMTypedMessage message, AVIMConversation conv, AVIMClient client);
    }

    public static onMessageReceiptCallBack onmessagecallbackReceipt1;

    public static void getMessageReceipt(onMessageReceiptCallBack onmessagecallbackReceipt) {
        onmessagecallbackReceipt1 = onmessagecallbackReceipt;
    }


    public void closeWithCallback(final AVIMClientCallback callback) {
        imClient.close(new AVIMClientCallback() {


            @Override
            public void done(AVIMClient client, AVIMException e) {
                if (e != null) {
                    Utils.logThrowable(e);
                }
                if (callback != null) {
                    callback.done(client, e);
                }
            }
        });
        imClient = null;
        selfId = null;
    }

    public AVIMConversationQuery getQuery() {
        return imClient.getQuery();
    }

    @Override
    public void onConnectionPaused(AVIMClient client) {
        connect = false;
        connectionListener.onConnectionChanged(connect);
    }

    @Override
    public void onConnectionResume(AVIMClient client) {
        connect = true;
        connectionListener.onConnectionChanged(connect);
    }

    @Override
    public void onClientOffline(AVIMClient avimClient, int i) {
        //TODO 需要测试
    }

   /* @Override
    public void onClientOffline(AVIMClient avimClient, int i) {

    }*/

    public boolean isConnect() {
        return connect;
    }

    //cache
    public void registerConversation(AVIMConversation conversation) {
        cachedConversations.put(conversation.getConversationId(), conversation);
    }

    public AVIMConversation lookUpConversationById(String conversationId) {
        return cachedConversations.get(conversationId);
    }

    public ChatManagerAdapter getChatManagerAdapter() {
        return chatManagerAdapter;
    }

    public void setChatManagerAdapter(ChatManagerAdapter chatManagerAdapter) {
        this.chatManagerAdapter = chatManagerAdapter;
    }

    //ChatUser
    public List<Room> findRecentRooms() {
        return RoomsTable.getCurrentUserInstance().selectRooms();
    }

    public interface ConnectionListener {
        void onConnectionChanged(boolean connect);
    }

    public static class MessageHandler extends AVIMTypedMessageHandler<AVIMTypedMessage> {

        @Override
        public void onMessage(AVIMTypedMessage message, AVIMConversation conversation,
                              AVIMClient client) {

            if (client.getClientId().equals(chatManager.getSelfId())) {
                chatManager.onMessage(message, conversation, client);
                messagesList.add(message);
            } else {
                // 收到其它的client的消息，可能是上一次别的client登录未正确关闭，这里关边掉。
                client.close(null);
            }
        }

        @Override
        public void onMessageReceipt(AVIMTypedMessage message, AVIMConversation conversation,
                                     AVIMClient client) {
            if (client.getClientId().equals(chatManager.getSelfId())) {
                chatManager.onMessageReceipt(message, conversation, client);
            } else {
                client.close(null);
            }
        }
    }

    /**
     * msgId 、time 共同使用，防止某毫秒时刻有重复消息
     */
    public void queryMessages(AVIMConversation conversation, String msgId, long time, int limit,
                              final AVIMTypedMessagesArrayCallback callback) {
        conversation.queryMessages(msgId, time, limit, new AVIMMessagesQueryCallback() {
            @Override
            public void done(List<AVIMMessage> imMessages, AVIMException e) {
                if (e != null) {
                    callback.done(new ArrayList<AVIMTypedMessage>(), e);
                } else {
                    List<AVIMTypedMessage> resultMessages = new ArrayList<>();
                    for (AVIMMessage msg : imMessages) {
                        if (msg instanceof AVIMTypedMessage) {
                            resultMessages.add((AVIMTypedMessage) msg);
                        }
                    }
                    callback.done(resultMessages, null);
                }
            }
        });
    }

    public void insertLatestMessage(AVIMTypedMessage message) {
        latestMessages.put(message.getConversationId(), message);
    }

    private AVIMTypedMessage getLatestMessage(String conversationId) {
        return latestMessages.get(conversationId);
    }

    public AVIMTypedMessage getOrQueryLatestMessage(String conversationId) throws InterruptedException {
        AVIMTypedMessage message = getLatestMessage(conversationId);
        if (message != null) {
            return message;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final List<AVIMTypedMessage> foundMessages = new ArrayList<>();
        queryMessages(imClient.getConversation(conversationId), null, System.currentTimeMillis(), 1, new AVIMTypedMessagesArrayCallback() {
            @Override
            public void done(List<AVIMTypedMessage> typedMessages, AVException e) {
                if (e == null) {
                    foundMessages.addAll(typedMessages);
                }
                latch.countDown();
            }
        });
        latch.await();
        if (foundMessages.size() > 0) {
            insertLatestMessage(foundMessages.get(0));
            return foundMessages.get(0);
        } else {
            return null;
        }
    }

}
