/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.dataconnection;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;

import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.provider.Settings;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.util.SparseArray;

import com.android.internal.os.SomeArgs;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.dataconnection.DcSwitchAsyncChannel.RequestInfo;
import com.android.internal.util.AsyncChannel;
import com.android.internal.telephony.dataconnection.DdsScheduler;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;

public class DctController extends Handler {
    private static final String LOG_TAG = "DctController";
    private static final boolean DBG = true;

    private static final int EVENT_PROCESS_REQUESTS = 100;
    private static final int EVENT_EXECUTE_REQUEST = 101;
    private static final int EVENT_EXECUTE_ALL_REQUESTS = 102;
    private static final int EVENT_RELEASE_REQUEST = 103;
    private static final int EVENT_RELEASE_ALL_REQUESTS = 104;
    private static final int EVENT_START_DDS_SWITCH = 105;

    private static final int EVENT_DATA_ATTACHED = 500;
    private static final int EVENT_DATA_DETACHED = 600;

    private static final int EVENT_DELAYED_RETRY = 1;
    private static final int EVENT_LEGACY_SET_DATA_SUBSCRIPTION = 2;
    private static final int EVENT_SET_DATA_ALLOW_FALSE = 3;

    private RegistrantList mNotifyDefaultDataSwitchInfo = new RegistrantList();
    private RegistrantList mNotifyOnDemandDataSwitchInfo = new RegistrantList();
    private RegistrantList mNotifyOnDemandPsAttach = new RegistrantList();
    private SubscriptionController mSubController = SubscriptionController.getInstance();

    private static DctController sDctController;

    private int mPhoneNum;
    private PhoneProxy[] mPhones;
    private DcSwitchStateMachine[] mDcSwitchStateMachine;
    private DcSwitchAsyncChannel[] mDcSwitchAsyncChannel;
    private Handler[] mDcSwitchStateHandler;
    private HashMap<Integer, RequestInfo> mRequestInfos = new HashMap<Integer, RequestInfo>();
    private Context mContext;

    private AsyncChannel mDdsSwitchPropService;
    private DdsSwitchSerializerHandler mDdsSwitchSerializer;
    private boolean mIsDdsSwitchCompleted = true;

    private final int MAX_RETRY_FOR_ATTACH = 6;
    private final int ATTACH_RETRY_DELAY = 1000 * 10;

    /** Used to send us NetworkRequests from ConnectivityService.  Remember it so we can
     * unregister on dispose. */
    private Messenger[] mNetworkFactoryMessenger;
    private NetworkFactory[] mNetworkFactory;
    private NetworkCapabilities[] mNetworkFilter;

    private SubscriptionController mSubController = SubscriptionController.getInstance();

    private ContentObserver mObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            logd("Settings change");
            onSettingsChange();
        }
    };

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver () {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            logd("onReceive: action=" + action);
            if (action.equals(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED)) {
                onSubInfoReady();
            }
        }
    };

    boolean isActiveSubId(long subId) {
        long[] activeSubs = mSubController.getActiveSubIdList();
        for (int i = 0; i < activeSubs.length; i++) {
            if (subId == activeSubs[i]) {
                return true;
            }
        }
        return false;
    }

    public void updatePhoneObject(PhoneProxy phone) {
        if(phone == null) {
            loge("updatePhoneObject phone = null");
            return;
        }
        PhoneBase phoneBase = (PhoneBase)phone.getActivePhone();
        if(phoneBase == null) {
            loge("updatePhoneObject phoneBase = null");
            return;
        }

        phoneBase.getServiceStateTracker().registerForDataConnectionAttached(mRspHandler,
                   EVENT_DATA_ATTACHED, null);
        phoneBase.getServiceStateTracker().registerForDataConnectionDetached(mRspHandler,
                   EVENT_DATA_DETACHED, null);

        for(int i = 0; i < mPhoneNum; i++) {
            if((mPhones[i] == phone) && (mNetworkFactory != null) && (mNetworkFactory[i] != null)){
                logd("updatePhoneObject for phone i=" + i);
                ((DctController.TelephonyNetworkFactory)mNetworkFactory[i]).setPhone(phoneBase);
                break;
            }
        }
    }

    private Handler mRspHandler = new Handler() {
        @Override
        public void handleMessage(Message msg){
            if (msg.what >= EVENT_DATA_DETACHED) {
                logd("EVENT_PHONE" + (msg.what - EVENT_DATA_DETACHED + 1)
                        + "_DATA_DETACH.");
                mDcSwitchAsyncChannel[msg.what - EVENT_DATA_DETACHED].notifyDataDetached();

            } else if (msg.what >= EVENT_DATA_ATTACHED) {
                logd("EVENT_PHONE" + (msg.what - EVENT_DATA_ATTACHED + 1)
                        + "_DATA_ATTACH.");
                mDcSwitchAsyncChannel[msg.what - EVENT_DATA_ATTACHED].notifyDataAttached();
            }
        }
    };

    public static DctController getInstance() {
       if (sDctController == null) {
        throw new RuntimeException(
            "DctController.getInstance can't be called before makeDCTController()");
        }
       return sDctController;
    }

    public static DctController makeDctController(PhoneProxy[] phones, Looper looper) {
        if (sDctController == null) {
            logd("makeDctController: new DctController phones.length=" + phones.length);
            sDctController = new DctController(phones, looper);
            DdsScheduler.init();
        }
        logd("makeDctController: X sDctController=" + sDctController);
        return sDctController;
    }

    private DctController(PhoneProxy[] phones, Looper looper) {
        super(looper);
        logd("DctController(): phones.length=" + phones.length);
        if (phones == null || phones.length == 0) {
            if (phones == null) {
                loge("DctController(phones): UNEXPECTED phones=null, ignore");
            } else {
                loge("DctController(phones): UNEXPECTED phones.length=0, ignore");
            }
            return;
        }
        mPhoneNum = phones.length;
        mPhones = phones;

        mDcSwitchStateMachine = new DcSwitchStateMachine[mPhoneNum];
        mDcSwitchAsyncChannel = new DcSwitchAsyncChannel[mPhoneNum];
        mDcSwitchStateHandler = new Handler[mPhoneNum];
        mNetworkFactoryMessenger = new Messenger[mPhoneNum];
        mNetworkFactory = new NetworkFactory[mPhoneNum];
        mNetworkFilter = new NetworkCapabilities[mPhoneNum];

        for (int i = 0; i < mPhoneNum; ++i) {
            int phoneId = i;
            mDcSwitchStateMachine[i] = new DcSwitchStateMachine(mPhones[i],
                    "DcSwitchStateMachine-" + phoneId, phoneId);
            mDcSwitchStateMachine[i].start();
            mDcSwitchAsyncChannel[i] = new DcSwitchAsyncChannel(mDcSwitchStateMachine[i], phoneId);
            mDcSwitchStateHandler[i] = new Handler();

            int status = mDcSwitchAsyncChannel[i].fullyConnectSync(mPhones[i].getContext(),
                mDcSwitchStateHandler[i], mDcSwitchStateMachine[i].getHandler());

            if (status == AsyncChannel.STATUS_SUCCESSFUL) {
                logd("DctController(phones): Connect success: " + i);
            } else {
                loge("DctController(phones): Could not connect to " + i);
            }

            // Register for radio state change
            PhoneBase phoneBase = (PhoneBase)mPhones[i].getActivePhone();

            phoneBase.getServiceStateTracker().registerForDataConnectionAttached(mRspHandler,
                   EVENT_DATA_ATTACHED + i, null);
            phoneBase.getServiceStateTracker().registerForDataConnectionDetached(mRspHandler,
                   EVENT_DATA_DETACHED + i, null);

            ConnectivityManager cm = (ConnectivityManager)mPhones[i].getContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);

            mNetworkFilter[i] = new NetworkCapabilities();
            mNetworkFilter[i].addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_SUPL);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_DUN);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_FOTA);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_IMS);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_CBS);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_IA);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_RCS);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_XCAP);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_EIMS);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

            mNetworkFactory[i] = new TelephonyNetworkFactory(this.getLooper(),
                    mPhones[i].getContext(), "TelephonyNetworkFactory", phoneBase,
                    mNetworkFilter[i]);
            mNetworkFactory[i].setScoreFilter(50);
            mNetworkFactoryMessenger[i] = new Messenger(mNetworkFactory[i]);
            cm.registerNetworkFactory(mNetworkFactoryMessenger[i], "Telephony");

        }

        mContext = mPhones[0].getContext();

        HandlerThread t = new HandlerThread("DdsSwitchSerializer");
        t.start();

        mDdsSwitchSerializer = new DdsSwitchSerializerHandler(t.getLooper());

        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);
        mContext.registerReceiver(mIntentReceiver, filter);

        //Register for settings change.
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(
                Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION),
                false, mObserver);
    }

    public void dispose() {
        logd("DctController.dispose");
        for (int i = 0; i < mPhoneNum; ++i) {
            ConnectivityManager cm = (ConnectivityManager)mPhones[i].getContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.unregisterNetworkFactory(mNetworkFactoryMessenger[i]);
            mNetworkFactoryMessenger[i] = null;
        }

        mContext.getContentResolver().unregisterContentObserver(mObserver);
    }

    @Override
    public void handleMessage (Message msg) {
        logd("handleMessage msg=" + msg);
        switch (msg.what) {
            case EVENT_PROCESS_REQUESTS:
                onProcessRequest();
                break;
            case EVENT_EXECUTE_REQUEST:
                onExecuteRequest((RequestInfo)msg.obj);
                break;
            case EVENT_EXECUTE_ALL_REQUESTS:
                onExecuteAllRequests(msg.arg1);
                break;
            case EVENT_RELEASE_REQUEST:
                onReleaseRequest((RequestInfo)msg.obj);
                break;
            case EVENT_RELEASE_ALL_REQUESTS:
                onReleaseAllRequests(msg.arg1);
                break;
            default:
                loge("Un-handled message [" + msg.what + "]");
        }
    }

    private int requestNetwork(NetworkRequest request, int priority) {
        logd("requestNetwork request=" + request
                + ", priority=" + priority);

        RequestInfo requestInfo = new RequestInfo(request, priority);
        mRequestInfos.put(request.requestId, requestInfo);
        processRequests();

        return PhoneConstants.APN_REQUEST_STARTED;
    }

    private int releaseNetwork(NetworkRequest request) {
        RequestInfo requestInfo = mRequestInfos.get(request.requestId);
        logd("releaseNetwork request=" + request + ", requestInfo=" + requestInfo);

        mRequestInfos.remove(request.requestId);
        releaseRequest(requestInfo);
        processRequests();
        return PhoneConstants.APN_REQUEST_STARTED;
    }

    void processRequests() {
        logd("processRequests");
        sendMessage(obtainMessage(EVENT_PROCESS_REQUESTS));
    }

    void executeRequest(RequestInfo request) {
        logd("executeRequest, request= " + request);
        sendMessage(obtainMessage(EVENT_EXECUTE_REQUEST, request));
    }

    void executeAllRequests(int phoneId) {
        logd("executeAllRequests, phone:" + phoneId);
        sendMessage(obtainMessage(EVENT_EXECUTE_ALL_REQUESTS, phoneId,0));
    }

    void releaseRequest(RequestInfo request) {
        logd("releaseRequest, request= " + request);
        sendMessage(obtainMessage(EVENT_RELEASE_REQUEST, request));
    }

    void releaseAllRequests(int phoneId) {
        logd("releaseAllRequests, phone:" + phoneId);
        sendMessage(obtainMessage(EVENT_RELEASE_ALL_REQUESTS, phoneId, 0));
    }

    private void onProcessRequest() {
        //process all requests
        //1. Check all requests and find subscription of the top priority
        //   request
        //2. Is current data allowed on the selected subscription
        //2-1. If yes, execute all the requests of the sub
        //2-2. If no, set data not allow on the current PS subscription
        //2-2-1. Set data allow on the selected subscription

        int phoneId = getTopPriorityRequestPhoneId();
        int activePhoneId = -1;

        for (int i=0; i<mDcSwitchStateMachine.length; i++) {
            if (!mDcSwitchAsyncChannel[i].isIdleSync()) {
                activePhoneId = i;
                break;
            }
        }

        logd("onProcessRequest phoneId=" + phoneId
                + ", activePhoneId=" + activePhoneId);

        if (activePhoneId == -1 || activePhoneId == phoneId) {
            Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
            while (iterator.hasNext()) {
                RequestInfo requestInfo = mRequestInfos.get(iterator.next());
                if (getRequestPhoneId(requestInfo.request) == phoneId && !requestInfo.executed) {
                    mDcSwitchAsyncChannel[phoneId].connectSync(requestInfo);
                }
            }
        } else {
            mDcSwitchAsyncChannel[activePhoneId].disconnectAllSync();
        }
    }

    private void onExecuteRequest(RequestInfo requestInfo) {
        logd("onExecuteRequest request=" + requestInfo);
        if (!requestInfo.executed) {
            requestInfo.executed = true;
            String apn = apnForNetworkRequest(requestInfo.request);
            int phoneId = getRequestPhoneId(requestInfo.request);
            PhoneBase phoneBase = (PhoneBase)mPhones[phoneId].getActivePhone();
            DcTrackerBase dcTracker = phoneBase.mDcTracker;
            dcTracker.incApnRefCount(apn);
        }
    }

    private void onExecuteAllRequests(int phoneId) {
        logd("onExecuteAllRequests phoneId=" + phoneId);
        Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
        while (iterator.hasNext()) {
            RequestInfo requestInfo = mRequestInfos.get(iterator.next());
            if (getRequestPhoneId(requestInfo.request) == phoneId) {
                onExecuteRequest(requestInfo);
            }
        }
    }

    private void onReleaseRequest(RequestInfo requestInfo) {
        logd("onReleaseRequest request=" + requestInfo);
        if (requestInfo.executed) {
            String apn = apnForNetworkRequest(requestInfo.request);
            int phoneId = getRequestPhoneId(requestInfo.request);
            PhoneBase phoneBase = (PhoneBase)mPhones[phoneId].getActivePhone();
            DcTrackerBase dcTracker = phoneBase.mDcTracker;
            dcTracker.decApnRefCount(apn);
            requestInfo.executed = false;
        }
    }

    private void onReleaseAllRequests(int phoneId) {
        logd("onReleaseAllRequests phoneId=" + phoneId);
        Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
        while (iterator.hasNext()) {
            RequestInfo requestInfo = mRequestInfos.get(iterator.next());
            if (getRequestPhoneId(requestInfo.request) == phoneId) {
                onReleaseRequest(requestInfo);
            }
        }
    }

    private void onSettingsChange() {
        //Sub Selection
        long dataSubId = mSubController.getDefaultDataSubId();

        int activePhoneId = -1;
        for (int i=0; i<mDcSwitchStateMachine.length; i++) {
            if (!mDcSwitchAsyncChannel[i].isIdleSync()) {
                activePhoneId = i;
                break;
            }
        }

        int[] subIds = SubscriptionManager.getSubId(activePhoneId);
        if (subIds ==  null || subIds.length == 0) {
            loge("onSettingsChange, subIds null or length 0 for activePhoneId " + activePhoneId);
            return;
        }
        logd("onSettingsChange, data sub: " + dataSubId + ", active data sub: " + subIds[0]);

        if (subIds[0] != dataSubId) {
            Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
            while (iterator.hasNext()) {
                RequestInfo requestInfo = mRequestInfos.get(iterator.next());
                String specifier = requestInfo.request.networkCapabilities.getNetworkSpecifier();
                if (specifier == null || specifier.equals("")) {
                    if (requestInfo.executed) {
                        String apn = apnForNetworkRequest(requestInfo.request);
                        logd("[setDataSubId] activePhoneId:" + activePhoneId + ", subId =" +
                                dataSubId);
                        PhoneBase phoneBase =
                                (PhoneBase)mPhones[activePhoneId].getActivePhone();
                        DcTrackerBase dcTracker = phoneBase.mDcTracker;
                        dcTracker.decApnRefCount(apn);
                        requestInfo.executed = false;
                    }
                }
            }
        }

        // Some request maybe pending due to invalid settings
        // Try to handle pending request when settings changed
        for (int i = 0; i < mPhoneNum; ++i) {
            ((DctController.TelephonyNetworkFactory)mNetworkFactory[i]).evalPendingRequest();
        }

        processRequests();
    }

    private int getTopPriorityRequestPhoneId() {
        RequestInfo retRequestInfo = null;
        int phoneId = 0;
        int priority = -1;

        //TODO: Handle SIM Switch
        for (int i=0; i<mPhoneNum; i++) {
            Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
            while (iterator.hasNext()) {
                RequestInfo requestInfo = mRequestInfos.get(iterator.next());
                logd("selectExecPhone requestInfo = " + requestInfo);
                if (getRequestPhoneId(requestInfo.request) == i &&
                        priority < requestInfo.priority) {
                    priority = requestInfo.priority;
                    retRequestInfo = requestInfo;
                }
            }
        }

        if (retRequestInfo != null) {
            phoneId = getRequestPhoneId(retRequestInfo.request);
        }

        logd("getTopPriorityRequestPhoneId = " + phoneId
                + ", priority = " + priority);

        return phoneId;
    }

    private static void logv(String s) {
        Rlog.v(LOG_TAG, "[DctController] " + s);
    }

    private static void logd(String s) {
        Rlog.d(LOG_TAG, "[DctController] " + s);
    }

    private static void logw(String s) {
        Rlog.w(LOG_TAG, "[DctController] " + s);
    }

    private static void loge(String s) {
        Rlog.e(LOG_TAG, "[DctController] " + s);
    }

/* FIXME - LMR1_INTERNAL Dds switch, On demand PS Attach/Detach proprietary logic
    private class SwitchInfo {
        private int mRetryCount = 0;

        public int mPhoneId;
        public NetworkRequest mNetworkRequest;
        public boolean mIsDefaultDataSwitchRequested;
        public boolean mIsOnDemandPsAttachRequested;

        public SwitchInfo(int phoneId, NetworkRequest n, boolean flag, boolean isAttachReq) {
            mPhoneId = phoneId;
            mNetworkRequest = n;
            mIsDefaultDataSwitchRequested = flag;
            mIsOnDemandPsAttachRequested = isAttachReq;
        }

        public SwitchInfo(int phoneId,boolean flag) {
            mPhoneId = phoneId;
            mNetworkRequest = null;
            mIsDefaultDataSwitchRequested = flag;
        }

        public void incRetryCount() {
            mRetryCount++;

        }

        public boolean isRetryPossible() {
            return (mRetryCount < MAX_RETRY_FOR_ATTACH);
        }

        public String toString() {
            return "SwitchInfo[phoneId = " + mPhoneId
                + ", NetworkRequest =" + mNetworkRequest
                + ", isDefaultSwitchRequested = " + mIsDefaultDataSwitchRequested
                + ", isOnDemandPsAttachRequested = " + mIsOnDemandPsAttachRequested
                + ", RetryCount = " + mRetryCount;
        }
    }

    private void doDetach(int phoneId) {
        Phone phone = mPhones[phoneId].getActivePhone();
        DcTrackerBase dcTracker =((PhoneBase)phone).mDcTracker;
        dcTracker.setDataAllowed(false, null);
        if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            //cleanup data from apss as there is no detach procedure for CDMA
            dcTracker.cleanUpAllConnections("DDS switch");
        }
    }
    public void setDefaultDataSubId(long reqSubId) {
        int reqPhoneId = mSubController.getPhoneId(reqSubId);
        long currentDds = mSubController.getCurrentDds();
        long defaultDds = mSubController.getDefaultDataSubId();
        SwitchInfo s = new SwitchInfo(new Integer(reqPhoneId), true);
        int currentDdsPhoneId = mSubController.getPhoneId(currentDds);
        if (currentDdsPhoneId < 0 || currentDdsPhoneId >= mPhoneNum) {
            // If Current dds subId is invalid set the received subId as current DDS
            // This generally happens when device power-up first time.
            logd(" setDefaultDataSubId,  reqSubId = " + reqSubId + " currentDdsPhoneId  "
                    + currentDdsPhoneId);
            mSubController.setDataSubId(reqSubId);
            defaultDds = reqSubId;
            currentDdsPhoneId = mSubController.getPhoneId(defaultDds);
        }
        Rlog.d(LOG_TAG, "setDefaultDataSubId reqSubId :" + reqSubId + " reqPhoneId = "
                + reqPhoneId);

        // Avoid sending data allow false and true on same sub .
        if ((reqSubId != defaultDds) && (reqPhoneId != currentDdsPhoneId)) {
            doDetach(currentDdsPhoneId);
        } else {
            logd("setDefaultDataSubId for default DDS, skip PS detach on DDS subs");
            sendMessage(obtainMessage(EVENT_LEGACY_SET_DATA_SUBSCRIPTION,
                        new AsyncResult(s, null, null)));
            return;
        }

        mPhones[currentDdsPhoneId].registerForAllDataDisconnected(
                this, EVENT_ALL_DATA_DISCONNECTED, s);
    }

    private void informDefaultDdsToPropServ(int defDdsPhoneId) {
        if (mDdsSwitchPropService != null) {
            logd("Inform OemHookDDS service of current DDS = " + defDdsPhoneId);
            mDdsSwitchPropService.sendMessageSynchronously(1, defDdsPhoneId,
                    mPhoneNum);
            logd("OemHookDDS service finished");
        } else {
            logd("OemHookDds service not ready yet");
        }

    }

    public void doPsAttach(NetworkRequest n) {
        Rlog.d(LOG_TAG, "doPsAttach for :" + n);

        long subId = mSubController.getSubIdFromNetworkRequest(n);

        int phoneId = mSubController.getPhoneId(subId);
        Phone phone = mPhones[phoneId].getActivePhone();
        DcTrackerBase dcTracker =((PhoneBase)phone).mDcTracker;

        //request only PS ATTACH on requested subscription.
        //No DdsSerealization lock required.
        SwitchInfo s = new SwitchInfo(new Integer(phoneId), n, false, true);

        Message psAttachDone = Message.obtain(this,
                EVENT_SET_DATA_ALLOW_TRUE_DONE, s);

        int defDdsPhoneId = getDataConnectionFromSetting();
        informDefaultDdsToPropServ(defDdsPhoneId);
        dcTracker.setDataAllowed(true, psAttachDone);
    }

    //
    // This is public API and client might call doPsDetach on DDS sub.
    // Ignore if thats the case.
    //
    public void doPsDetach() {
        long currentDds = mSubController.getCurrentDds();
        long defaultDds = mSubController.getDefaultDataSubId();

        if (currentDds == defaultDds) {
            Rlog.d(LOG_TAG, "PS DETACH on DDS sub is not allowed.");
            return;
        }
        Rlog.d(LOG_TAG, "doPsDetach for sub:" + currentDds);

        int phoneId = mSubController.getPhoneId(
                mSubController.getCurrentDds());

        Phone phone = mPhones[phoneId].getActivePhone();
        DcTrackerBase dcTracker =((PhoneBase)phone).mDcTracker;
        dcTracker.setDataAllowed(false, null);
    }

    public void setOnDemandDataSubId(NetworkRequest n) {
        Rlog.d(LOG_TAG, "setDataAllowed for :" + n);
        mDdsSwitchSerializer.sendMessage(mDdsSwitchSerializer
                .obtainMessage(EVENT_START_DDS_SWITCH, n));
    }

    public void registerForDefaultDataSwitchInfo(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        synchronized (mNotifyDefaultDataSwitchInfo) {
            mNotifyDefaultDataSwitchInfo.add(r);
        }
    }

    public void registerForOnDemandDataSwitchInfo(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        synchronized (mNotifyOnDemandDataSwitchInfo) {
            mNotifyOnDemandDataSwitchInfo.add(r);
        }
    }

    public void registerForOnDemandPsAttach(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        synchronized (mNotifyOnDemandPsAttach) {
            mNotifyOnDemandPsAttach.add(r);
        }
    }

    public void registerDdsSwitchPropService(Messenger messenger) {
        logd("Got messenger from DDS switch service, messenger = " + messenger);
        AsyncChannel ac = new AsyncChannel();
        ac.connect(mContext, sDctController, messenger);
    }

    @Override
        public void handleMessage (Message msg) {
            boolean isLegacySetDds = false;
            Rlog.d(LOG_TAG, "handleMessage msg=" + msg);

            switch (msg.what) {
                case EVENT_LEGACY_SET_DATA_SUBSCRIPTION:
                    isLegacySetDds = true;
                    //intentional fall through, no break.
                case EVENT_ALL_DATA_DISCONNECTED: {
                    AsyncResult ar = (AsyncResult)msg.obj;
                    SwitchInfo s = (SwitchInfo)ar.userObj;
                    Integer phoneId = s.mPhoneId;
                    Rlog.d(LOG_TAG, "EVENT_ALL_DATA_DISCONNECTED switchInfo :" + s +
                            " isLegacySetDds = " + isLegacySetDds);
                    // In this case prefPhoneId points to the newDds we are trying to
                    // set, hence we do not need to call unregister for data disconnected
                    if (!isLegacySetDds) {
                        int prefPhoneId = mSubController.getPhoneId(
                                 mSubController.getCurrentDds());
                        mPhones[prefPhoneId].unregisterForAllDataDisconnected(this);
                    }
                    Message allowedDataDone = Message.obtain(this,
                            EVENT_SET_DATA_ALLOW_TRUE_DONE, s);
                    Phone phone = mPhones[phoneId].getActivePhone();

                    informDefaultDdsToPropServ(phoneId);

                    DcTrackerBase dcTracker =((PhoneBase)phone).mDcTracker;
                    dcTracker.setDataAllowed(true, allowedDataDone);

                   break;
                }

                case EVENT_DELAYED_RETRY: {
                    Rlog.d(LOG_TAG, "EVENT_DELAYED_RETRY");
                    SwitchInfo s = (SwitchInfo)msg.obj;
                    Rlog.d(LOG_TAG, " Retry, switchInfo = " + s);

                    Integer phoneId = s.mPhoneId;
                    long[] subId = mSubController.getSubId(phoneId);

                    Message psAttachDone = Message.obtain(this,
                            EVENT_SET_DATA_ALLOW_TRUE_DONE, s);
                    Phone phone = mPhones[phoneId].getActivePhone();
                    DcTrackerBase dcTracker =((PhoneBase)phone).mDcTracker;
                    dcTracker.setDataAllowed(true, psAttachDone);
                    break;
                }

                case EVENT_SET_DATA_ALLOW_TRUE_DONE: {
                    AsyncResult ar = (AsyncResult)msg.obj;
                    SwitchInfo s = (SwitchInfo)ar.userObj;

                    Exception errorEx = null;

                    Integer phoneId = s.mPhoneId;
                    long[] subId = mSubController.getSubId(phoneId);
                    Rlog.d(LOG_TAG, "EVENT_SET_DATA_ALLOW_TRUE_DONE  subId :" + subId[0]
                            + ", switchInfo = " + s);

                    if (ar.exception != null) {
                        Rlog.d(LOG_TAG, "Failed, switchInfo = " + s
                                + " attempt delayed retry");
                        s.incRetryCount();
                        if ( s.isRetryPossible()) {
                            sendMessageDelayed(obtainMessage(EVENT_DELAYED_RETRY, s),
                                    ATTACH_RETRY_DELAY);
                            return;
                        } else {
                            Rlog.d(LOG_TAG, "Already did max retries, notify failure");
                            errorEx = new RuntimeException("PS ATTACH failed");
                       }
                    } else {
                        Rlog.d(LOG_TAG, "PS ATTACH success = " + s);
                    }

                    mDdsSwitchSerializer.unLock();

                    if (s.mIsDefaultDataSwitchRequested) {
                        mNotifyDefaultDataSwitchInfo.notifyRegistrants(
                                new AsyncResult(null, subId[0], errorEx));
                    } else if (s.mIsOnDemandPsAttachRequested) {
                        mNotifyOnDemandPsAttach.notifyRegistrants(
                                new AsyncResult(null, s.mNetworkRequest, errorEx));
                    } else {
                        mNotifyOnDemandDataSwitchInfo.notifyRegistrants(
                                new AsyncResult(null, s.mNetworkRequest, errorEx));
                    }
                    break;
                }

                case EVENT_SET_DATA_ALLOW_FALSE_DONE: {
                    AsyncResult ar = (AsyncResult)msg.obj;
                    SwitchInfo s = (SwitchInfo)ar.userObj;
                    Exception errorEx = null;
                    long[] subId = mSubController.getSubId(s.mPhoneId);
                    Rlog.d(LOG_TAG, "EVENT_SET_DATA_ALLOW_FALSE_DONE  subId :" + subId[0]
                            + ", switchInfo = " + s);

                    if (ar.exception != null) {
                        Rlog.d(LOG_TAG, "PS DETACH Failed, switchInfo = " + s);
                        errorEx = new RuntimeException("PS DETACH failed");
                        mDdsSwitchSerializer.unLock();
                        mNotifyOnDemandDataSwitchInfo.notifyRegistrants(
                                new AsyncResult(null, s.mNetworkRequest, errorEx));
                    } else {
                        Rlog.d(LOG_TAG, "PS DETACH success = " + s);
                    }
                    break;
                }

                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED: {
                    if(msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        logd("HALF_CONNECTED: Connection successful with DDS switch"
                                + " service");
                        mDdsSwitchPropService = (AsyncChannel) msg.obj;
                    } else {
                        logd("HALF_CONNECTED: Connection failed with"
                                +" DDS switch service, err = " + msg.arg1);
                    }
                       break;
                }

                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    logd("Connection disconnected with DDS switch service");
                    mDdsSwitchPropService = null;
                    break;
                }
        }
    }

    class DdsSwitchSerializerHandler extends Handler {
        final static String TAG = "DdsSwitchSerializer";

        public DdsSwitchSerializerHandler(Looper looper) {
            super(looper);
        }

        public void unLock() {
            Rlog.d(TAG, "unLock the DdsSwitchSerializer");
            synchronized(this) {
                mIsDdsSwitchCompleted = true;
                Rlog.d(TAG, "unLocked the DdsSwitchSerializer");
                notifyAll();
            }

        }

        public boolean isLocked() {
            synchronized(this) {
                Rlog.d(TAG, "isLocked = " + !mIsDdsSwitchCompleted);
                return !mIsDdsSwitchCompleted;
            }

        }

        @Override
        public void handleMessage (Message msg) {
            switch(msg.what) {
                case EVENT_START_DDS_SWITCH: {
                    Rlog.d(TAG, "EVENT_START_DDS_SWITCH");

                    try {
                        synchronized(this) {
                            while(!mIsDdsSwitchCompleted) {
                                Rlog.d(TAG, "DDS switch in progress, wait");
                                wait();
                            }

                            Rlog.d(TAG, "Locked!");
                            mIsDdsSwitchCompleted = false;
                        }
                    } catch (Exception e) {
                        Rlog.d(TAG, "Exception while serializing the DDS"
                                + " switch request , e=" + e);
                        return;
                    }

                    NetworkRequest n = (NetworkRequest)msg.obj;

                    Rlog.d(TAG, "start the DDS switch for req " + n);
                    long subId = mSubController.getSubIdFromNetworkRequest(n);

                    if(subId == mSubController.getCurrentDds()) {
                        Rlog.d(TAG, "No change in DDS, respond back");
                        mIsDdsSwitchCompleted = true;
                        mNotifyOnDemandDataSwitchInfo.notifyRegistrants(
                                new AsyncResult(null, n, null));
                        return;
                    }
                    int phoneId = mSubController.getPhoneId(subId);
                    int prefPhoneId = mSubController.getPhoneId(
                            mSubController.getCurrentDds());
                    Phone phone = mPhones[prefPhoneId].getActivePhone();
                    DcTrackerBase dcTracker =((PhoneBase)phone).mDcTracker;
                    SwitchInfo prefSwitchInfo = new SwitchInfo(new Integer(prefPhoneId), n, false,
                            false);
                    Message dataAllowFalse = Message.obtain(DctController.this,
                            EVENT_SET_DATA_ALLOW_FALSE_DONE, prefSwitchInfo);
                    dcTracker.setDataAllowed(false, dataAllowFalse);

                    if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
                        //cleanup data from apss as there is no detach procedure for CDMA
                        dcTracker.cleanUpAllConnections("Ondemand DDS switch");
                    }
                    SwitchInfo s = new SwitchInfo(new Integer(phoneId), n, false, false);
                    mPhones[prefPhoneId].registerForAllDataDisconnected(
                            sDctController, EVENT_ALL_DATA_DISCONNECTED, s);
                    break;
                }
            }
        }
    }
    public boolean isDctControllerLocked() {
        return mDdsSwitchSerializer.isLocked();
    }
*/

    private void onSubInfoReady() {
        logd("onSubInfoReady handle pending requset");
        for (int i = 0; i < mPhoneNum; ++i) {
            mNetworkFilter[i].setNetworkSpecifier(String.valueOf(mPhones[i].getSubId()));
            ((DctController.TelephonyNetworkFactory)mNetworkFactory[i]).evalPendingRequest();
        }
    }

    private String apnForNetworkRequest(NetworkRequest nr) {
        NetworkCapabilities nc = nr.networkCapabilities;
        // For now, ignore the bandwidth stuff
        if (nc.getTransportTypes().length > 0 &&
                nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == false) {
            return null;
        }

        // in the near term just do 1-1 matches.
        // TODO - actually try to match the set of capabilities
        int type = -1;
        String name = null;

        boolean error = false;
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_DEFAULT;
            type = ConnectivityManager.TYPE_MOBILE;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_MMS)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_MMS;
            type = ConnectivityManager.TYPE_MOBILE_MMS;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_SUPL)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_SUPL;
            type = ConnectivityManager.TYPE_MOBILE_SUPL;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_DUN)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_DUN;
            type = ConnectivityManager.TYPE_MOBILE_DUN;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOTA)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_FOTA;
            type = ConnectivityManager.TYPE_MOBILE_FOTA;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_IMS;
            type = ConnectivityManager.TYPE_MOBILE_IMS;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_CBS)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_CBS;
            type = ConnectivityManager.TYPE_MOBILE_CBS;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_IA)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_IA;
            type = ConnectivityManager.TYPE_MOBILE_IA;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_RCS)) {
            if (name != null) error = true;
            name = null;
            loge("RCS APN type not yet supported");
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_XCAP)) {
            if (name != null) error = true;
            name = null;
            loge("XCAP APN type not yet supported");
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_EIMS)) {
            if (name != null) error = true;
            name = null;
            loge("EIMS APN type not yet supported");
        }
        if (error) {
            loge("Multiple apn types specified in request - result is unspecified!");
        }
        if (type == -1 || name == null) {
            loge("Unsupported NetworkRequest in Telephony: nr=" + nr);
            return null;
        }
        return name;
    }

    private int getRequestPhoneId(NetworkRequest networkRequest) {
        String specifier = networkRequest.networkCapabilities.getNetworkSpecifier();
        int subId;
        if (specifier == null || specifier.equals("")) {
            subId = mSubController.getDefaultDataSubId();
        } else {
            subId = Integer.parseInt(specifier);
        }
        int phoneId = mSubController.getPhoneId(subId);
        if (!SubscriptionManager.isValidPhoneId(phoneId)) {
            phoneId = 0;
            if (!SubscriptionManager.isValidPhoneId(phoneId)) {
                throw new RuntimeException("Should not happen, no valid phoneId");
            }
        }
        return phoneId;
    }

    private static void logd(String s) {
        if (DBG) Rlog.d(LOG_TAG, s);
    }

    private static void loge(String s) {
        if (DBG) Rlog.e(LOG_TAG, s);
    }

    private class TelephonyNetworkFactory extends NetworkFactory {
        private final SparseArray<NetworkRequest> mPendingReq = new SparseArray<NetworkRequest>();
        private Phone mPhone;

        public void setPhone(Phone phone) {
            log("NetworkCapabilities: setPhone=" + phone);
            mPhone = phone;
        }

        public TelephonyNetworkFactory(Looper l, Context c, String TAG, Phone phone,
                NetworkCapabilities nc) {
            super(l, c, TAG, nc);
            mPhone = phone;
            log("NetworkCapabilities: " + nc);
        }

        @Override
        protected void needNetworkFor(NetworkRequest networkRequest, int score) {
            // figure out the apn type and enable it
            log("Cellular needs Network for " + networkRequest);

            if (!SubscriptionManager.isUsableSubIdValue(mPhone.getSubId())) {
                log("Sub Info has not been ready, pending request.");
                mPendingReq.put(networkRequest.requestId, networkRequest);
                return;
            }

            if (getRequestPhoneId(networkRequest) == mPhone.getPhoneId()) {
                DcTrackerBase dcTracker =((PhoneBase)mPhone).mDcTracker;
                String apn = apnForNetworkRequest(networkRequest);
                if (dcTracker.isApnSupported(apn)) {
                    requestNetwork(networkRequest, dcTracker.getApnPriority(apn));
                } else {
                    log("Unsupported APN");
                }
            } else {
                log("Request not send, put to pending");
                mPendingReq.put(networkRequest.requestId, networkRequest);
            }
        }

        @Override
        protected void releaseNetworkFor(NetworkRequest networkRequest) {
            log("Cellular releasing Network for " + networkRequest);

            if (!SubscriptionManager.isUsableSubIdValue(mPhone.getSubId())) {
                log("Sub Info has not been ready, remove request.");
                mPendingReq.remove(networkRequest.requestId);
                return;
            }

            if (getRequestPhoneId(networkRequest) == mPhone.getPhoneId()) {
                DcTrackerBase dcTracker =((PhoneBase)mPhone).mDcTracker;
                String apn = apnForNetworkRequest(networkRequest);
                if (dcTracker.isApnSupported(apn)) {
                    releaseNetwork(networkRequest);
                } else {
                    log("Unsupported APN");
                }

            } else {
                log("Request not release");
            }
        }

        @Override
        protected void log(String s) {
            if (DBG) Rlog.d(LOG_TAG, "[TNF " + mPhone.getSubId() + "]" + s);
        }

        public void evalPendingRequest() {
            log("evalPendingRequest, pending request size is " + mPendingReq.size());
            int key = 0;
            for(int i = 0; i < mPendingReq.size(); i++) {
                key = mPendingReq.keyAt(i);
                NetworkRequest request = mPendingReq.get(key);
                log("evalPendingRequest: request = " + request);

                mPendingReq.remove(request.requestId);
                needNetworkFor(request, 0);
            }
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("DctController:");
        try {
            for (DcSwitchStateMachine dssm : mDcSwitchStateMachine) {
                dssm.dump(fd, pw, args);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");

        try {
            for (Entry<Integer, RequestInfo> entry : mRequestInfos.entrySet()) {
                pw.println("mRequestInfos[" + entry.getKey() + "]=" + entry.getValue());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");
        pw.flush();
    }
}
