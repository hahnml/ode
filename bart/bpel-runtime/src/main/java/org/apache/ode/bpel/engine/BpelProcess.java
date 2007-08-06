/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ode.bpel.engine;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

import javax.wsdl.Operation;
import javax.xml.namespace.QName;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ode.bpel.common.FaultException;
import org.apache.ode.bpel.dao.BpelDAOConnection;
import org.apache.ode.bpel.dao.MessageExchangeDAO;
import org.apache.ode.bpel.dao.ProcessDAO;
import org.apache.ode.bpel.dao.ProcessInstanceDAO;
import org.apache.ode.bpel.evt.ProcessInstanceEvent;
import org.apache.ode.bpel.evt.ScopeEvent;
import org.apache.ode.bpel.explang.ConfigurationException;
import org.apache.ode.bpel.explang.EvaluationException;
import org.apache.ode.bpel.iapi.BpelEngineException;
import org.apache.ode.bpel.iapi.Endpoint;
import org.apache.ode.bpel.iapi.EndpointReference;
import org.apache.ode.bpel.iapi.InvocationStyle;
import org.apache.ode.bpel.iapi.MessageExchange;
import org.apache.ode.bpel.iapi.MyRoleMessageExchange;
import org.apache.ode.bpel.iapi.PartnerRoleChannel;
import org.apache.ode.bpel.iapi.ProcessConf;
import org.apache.ode.bpel.iapi.MessageExchange.AckType;
import org.apache.ode.bpel.iapi.MessageExchange.Status;
import org.apache.ode.bpel.iapi.MyRoleMessageExchange.CorrelationStatus;
import org.apache.ode.bpel.iapi.Scheduler.JobInfo;
import org.apache.ode.bpel.iapi.Scheduler.JobProcessorException;
import org.apache.ode.bpel.intercept.InterceptorInvoker;
import org.apache.ode.bpel.intercept.MessageExchangeInterceptor;
import org.apache.ode.bpel.memdao.BpelDAOConnectionFactoryImpl;
import org.apache.ode.bpel.o.OExpressionLanguage;
import org.apache.ode.bpel.o.OPartnerLink;
import org.apache.ode.bpel.o.OProcess;
import org.apache.ode.bpel.o.Serializer;
import org.apache.ode.bpel.runtime.ExpressionLanguageRuntimeRegistry;
import org.apache.ode.bpel.runtime.PROCESS;
import org.apache.ode.bpel.runtime.PropertyAliasEvaluationContext;
import org.apache.ode.bpel.runtime.channels.FaultData;
import org.apache.ode.jacob.soup.ReplacementMap;
import org.apache.ode.utils.GUID;
import org.apache.ode.utils.ObjectPrinter;
import org.apache.ode.utils.msg.MessageBundle;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

/**
 * Entry point into the runtime of a BPEL process.
 * 
 * @author Maciej Szefler <mszefler at gmail dot com>
 * @author Matthieu Riou <mriou at apache dot org>
 */
class BpelProcess {
    static final Log __log = LogFactory.getLog(BpelProcess.class);

    private static final Messages __msgs = MessageBundle.getMessages(Messages.class);

    private volatile Map<OPartnerLink, PartnerLinkPartnerRoleImpl> _partnerRoles;

    private volatile Map<OPartnerLink, PartnerLinkMyRoleImpl> _myRoles;

    /** Mapping from {"Service Name" (QNAME) / port} to a myrole. */
    private volatile Map<Endpoint, PartnerLinkMyRoleImpl> _endpointToMyRoleMap;

    // Backup hashmaps to keep initial endpoints handy after dehydration
    private Map<Endpoint, EndpointReference> _myEprs = new HashMap<Endpoint, EndpointReference>();

    private Map<Endpoint, EndpointReference> _partnerEprs = new HashMap<Endpoint, EndpointReference>();

    private Map<Endpoint, PartnerRoleChannel> _partnerChannels = new HashMap<Endpoint, PartnerRoleChannel>();

    final QName _pid;

    private volatile OProcess _oprocess;

    // Has the process already been hydrated before?
    private boolean _hydratedOnce = false;

    /** Last time the process was used. */
    private volatile long _lastUsed;

    DebuggerSupport _debugger;

    ExpressionLanguageRuntimeRegistry _expLangRuntimeRegistry;

    private ReplacementMap _replacementMap;

    final ProcessConf _pconf;

    /** {@link MessageExchangeInterceptor}s registered for this process. */
    private final List<MessageExchangeInterceptor> _mexInterceptors = new ArrayList<MessageExchangeInterceptor>();

    /** Latch-like thing to control hydration/dehydration. */
    HydrationLatch _hydrationLatch;

    protected Contexts _contexts;

    final BpelInstanceWorkerCache _instanceWorkerCache = new BpelInstanceWorkerCache(this);

    private final Set<InvocationStyle> _invocationStyles;

    private BpelDAOConnectionFactoryImpl _inMemDao;

    private Random _random = new Random();

    final BpelServerImpl _server;

    final private List<WeakReference<MyRoleMessageExchangeImpl>> _mexStateListeners = new CopyOnWriteArrayList<WeakReference<MyRoleMessageExchangeImpl>>();

    BpelProcess(BpelServerImpl server, ProcessConf conf, BpelEventListener debugger) {
        _server = server;
        _pid = conf.getProcessId();
        _pconf = conf;
        _hydrationLatch = new HydrationLatch();
        _contexts = server._contexts;
        _inMemDao = new BpelDAOConnectionFactoryImpl(_contexts.txManager);

        // TODO : do this on a per-partnerlink basis, support transacted styles.
        HashSet<InvocationStyle> istyles = new HashSet<InvocationStyle>();
        istyles.add(InvocationStyle.UNRELIABLE);

        if (!conf.isTransient()) {
            istyles.add(InvocationStyle.RELIABLE);
        } else {
            istyles.add(InvocationStyle.TRANSACTED);
        }

        _invocationStyles = Collections.unmodifiableSet(istyles);
    }

    public String toString() {
        return "BpelProcess[" + _pid + "]";
    }

    void recoverActivity(ProcessInstanceDAO instanceDAO, String channel, long activityId, String action, FaultData fault) {
        if (__log.isDebugEnabled())
            __log.debug("Recovering activity in process " + instanceDAO.getInstanceId() + " with action " + action);
        markused();
        throw new AssertionError("TODO: fixme");// TODO
        // BpelRuntimeContextImpl processInstance = createRuntimeContext(instanceDAO, null, null);
        // processInstance.recoverActivity(channel, activityId, action, fault);
    }

    /**
     * Entry point for message exchanges aimed at the my role.
     * 
     * @param mex
     */
    void invokeProcess(final MessageExchangeDAO mexdao) {
        InvocationStyle istyle = InvocationStyle.valueOf(mexdao.getInvocationStyle());

        _hydrationLatch.latch(1);
        try {
            // The following check is mostly for sanity purposes. MexImpls should prevent this from 
            // happening. 
            PartnerLinkMyRoleImpl target = getMyRoleForService(mexdao.getCallee());
            Status oldstatus = Status.valueOf(mexdao.getStatus());
            if (target == null) {
                String errmsg = __msgs.msgMyRoleRoutingFailure(mexdao.getMessageExchangeId());
                __log.error(errmsg);
                mexdao.setFailureType(MessageExchange.FailureType.UNKNOWN_ENDPOINT.toString());
                mexdao.setFaultExplanation(errmsg);
                mexdao.setStatus(Status.ACK.toString());
                mexdao.setAckType(AckType.FAILURE);
                fireMexStateEvent(mexdao, oldstatus, Status.ACK);
                return;
            }

            Operation op = target._plinkDef.getMyRoleOperation(mexdao.getOperation());
            if (op == null) {
                String errmsg = __msgs.msgMyRoleRoutingFailure(mexdao.getMessageExchangeId());
                __log.error(errmsg);
                mexdao.setFailureType(MessageExchange.FailureType.UNKNOWN_OPERATION.toString());
                mexdao.setFaultExplanation(errmsg);
                mexdao.setStatus(Status.ACK.toString());
                mexdao.setAckType(AckType.FAILURE);
                fireMexStateEvent(mexdao, oldstatus, Status.ACK);
                return;
            }

            // "Acknowledge" any one-way invokes 
            if (op.getOutput() == null) {
                mexdao.setStatus(Status.ACK.toString());
                mexdao.setAckType(AckType.ONEWAY);
                fireMexStateEvent(mexdao, oldstatus, Status.ACK);
            }
            
            mexdao.setProcess(getProcessDAO());

            // TODO: fix this
            // if (!processInterceptors(mex, InterceptorInvoker.__onProcessInvoked)) {
            // __log.debug("Aborting processing of mex " + mex + " due to interceptors.");
            // return;
            // }

            markused();
            CorrelationStatus cstatus = target.invokeMyRole(mexdao);
            if (cstatus == null) {
                ; // do nothing
            } else if (cstatus == CorrelationStatus.CREATE_INSTANCE) {
                doInstanceWork(mexdao.getInstance().getInstanceId(), new Callable<Void>() {
                    public Void call() {
                        executeCreateInstance(mexdao);
                        return null;
                    }
                });

            } else if (cstatus == CorrelationStatus.MATCHED) {
                doInstanceWork(mexdao.getInstance().getInstanceId(), new Callable<Void>() {
                    public Void call() {
                        executeContinueInstanceMyRoleRequestReceived(mexdao);
                        return null;
                    }
                });

            } else if (cstatus == CorrelationStatus.QUEUED) {
                ; // do nothing
            }
            // TODO: handle correlation status (i.e. execute instance).
        } finally {
            _hydrationLatch.release(1);
        }

    }

    void executeCreateInstance(MessageExchangeDAO mexdao) {
        assert _hydrationLatch.isLatched(1);

        BpelInstanceWorker worker = _instanceWorkerCache.get(mexdao.getInstance().getInstanceId());
        assert worker.isWorkerThread();
        BpelRuntimeContextImpl instanceCtx = new BpelRuntimeContextImpl(worker, mexdao.getInstance(), new PROCESS(_oprocess),
                mexdao);
        instanceCtx.execute();
    }

    void executeContinueInstanceMyRoleRequestReceived(MessageExchangeDAO mexdao) {
        assert _hydrationLatch.isLatched(1);

        BpelInstanceWorker worker = _instanceWorkerCache.get(mexdao.getInstance().getInstanceId());
        assert worker.isWorkerThread();

        BpelRuntimeContextImpl instance = new BpelRuntimeContextImpl(worker, mexdao.getInstance(), null, null);
        int amp = mexdao.getChannel().indexOf('&');
        String groupId = mexdao.getChannel().substring(0, amp);
        int idx = Integer.valueOf(mexdao.getChannel().substring(amp + 1));
        instance.inputMsgMatch(groupId, idx, mexdao);
        instance.execute();
    }

    void executeContinueInstancePartnerRoleResponseReceived(MessageExchangeDAO mexdao) {
        assert _hydrationLatch.isLatched(1);
        BpelInstanceWorker worker = _instanceWorkerCache.get(mexdao.getInstance().getInstanceId());
        assert worker.isWorkerThread();

//      TODO: we need a way to check if the lastBRC is indeed the lastBRC (serial number on the instanceDAO)
//        BpelRuntimeContextImpl brc = lastBRC == null ? new BpelRuntimeContextImpl(worker, mexdao.getInstance(), null, null)
//        : new BpelRuntimeContextImpl(worker, mexdao.getInstance(), lastBRC);
        BpelRuntimeContextImpl brc = new BpelRuntimeContextImpl(worker, mexdao.getInstance(), null, null);

        brc.injectPartnerResponse(mexdao.getMessageExchangeId(), mexdao.getChannel());
        brc.execute();
    }

    private void enqueueInstanceWork(Long instanceId, Runnable runnable) {
        BpelInstanceWorker iworker = _instanceWorkerCache.get(instanceId);
        iworker.enqueue(runnable);
    }

    private void enqueueInstanceTransaction(Long instanceId, final Runnable runnable) {
        BpelInstanceWorker iworker = _instanceWorkerCache.get(instanceId);
        iworker.enqueue(_server.new TransactedRunnable(runnable));
    }

    /**
     * Schedule work for a given instance; work will occur if transaction commits.
     * 
     * @param instanceId
     * @param name
     */
    void scheduleInstanceWork(final Long instanceId, final Runnable runnable) {
        _contexts.registerCommitSynchronizer(new Runnable() {
            public void run() {
                BpelInstanceWorker iworker = _instanceWorkerCache.get(instanceId);
                iworker.enqueue(new ProcessRunnable(runnable));
            }
        });

    }

    private <T> T doInstanceWork(Long instanceId, final Callable<T> callable) {
        try {
            BpelInstanceWorker iworker = _instanceWorkerCache.get(instanceId);
            return iworker.execInCurrentThread(new ProcessCallable<T>(callable));

        } catch (Exception ex) {
            throw new BpelEngineException(ex);
        }
    }

    private PartnerLinkMyRoleImpl getMyRoleForService(QName serviceName) {
        assert _hydrationLatch.isLatched(1);

        for (Map.Entry<Endpoint, PartnerLinkMyRoleImpl> e : _endpointToMyRoleMap.entrySet()) {
            if (e.getKey().serviceName.equals(serviceName))
                return e.getValue();
        }
        return null;
    }

    /**
     * Extract the value of a BPEL property from a BPEL messsage variable.
     * 
     * @param msgData
     *            message variable data
     * @param alias
     *            alias to apply
     * @param target
     *            description of the data (for error logging only)
     * @return value of the property
     * @throws FaultException
     */
    String extractProperty(Element msgData, OProcess.OPropertyAlias alias, String target) throws FaultException {
        markused();
        PropertyAliasEvaluationContext ectx = new PropertyAliasEvaluationContext(msgData, alias);
        Node lValue = ectx.getRootNode();

        if (alias.location != null)
            try {
                lValue = _expLangRuntimeRegistry.evaluateNode(alias.location, ectx);
            } catch (EvaluationException ec) {
                throw new FaultException(getOProcess().constants.qnSelectionFailure, alias.getDescription());
            }

        if (lValue == null) {
            String errmsg = __msgs.msgPropertyAliasReturnedNullSet(alias.getDescription(), target);
            if (__log.isErrorEnabled()) {
                __log.error(errmsg);
            }
            throw new FaultException(getOProcess().constants.qnSelectionFailure, errmsg);
        }

        if (lValue.getNodeType() == Node.ELEMENT_NODE) {
            // This is a bit hokey, we concatenate all the children's values; we
            // really should be checking to make sure that we are only dealing
            // with
            // text and attribute nodes.
            StringBuffer val = new StringBuffer();
            NodeList nl = lValue.getChildNodes();
            for (int i = 0; i < nl.getLength(); ++i) {
                Node n = nl.item(i);
                val.append(n.getNodeValue());
            }
            return val.toString();
        } else if (lValue.getNodeType() == Node.TEXT_NODE) {
            return ((Text) lValue).getWholeText();
        } else
            return null;
    }

    /**
     * Process the message-exchange interceptors.
     * 
     * @param mex
     *            message exchange
     * @return <code>true</code> if execution should continue, <code>false</code> otherwise
     */
    boolean processInterceptors(MyRoleMessageExchangeImpl mex, InterceptorInvoker invoker) {
        // InterceptorContextImpl ictx = new InterceptorContextImpl(_contexts.dao.getConnection(), getProcessDAO(), _pconf);
        //
        // for (MessageExchangeInterceptor i : _mexInterceptors)
        // if (!mex.processInterceptor(i, mex, ictx, invoker))
        // return false;
        // for (MessageExchangeInterceptor i : getEngine().getInterceptors())
        // if (!mex.processInterceptor(i, mex, ictx, invoker))
        // return false;
        //
        return true;
    }

    /**
     * Handle a work event; this method is called from the scheduler thread and should be very quick, i.e. any serious work needs to
     * be handed off to a separate thread.
     * 
     * @throws JobProcessorException
     * @see org.apache.ode.bpel.engine.BpelProcess#handleWorkEvent(java.util.Map<java.lang.String,java.lang.Object>)
     */
    void handleWorkEvent(final JobInfo jobInfo) throws JobProcessorException {
        assert !_contexts.isTransacted() : "work events must be received outside of a transaction";

        markused();

        final WorkEvent we = new WorkEvent(jobInfo.jobDetail);
        if (__log.isDebugEnabled()) {
            __log.debug(ObjectPrinter.stringifyMethodEnter("handleWorkEvent", new Object[] { "jobInfo", jobInfo }));
        }

        // Process-level events
        if (we.getType().equals(WorkEvent.Type.MYROLE_INVOKE)) {
            // second stage of my-role invoke for BLOCKING/ASYNC/RELIABLE invocation style.
            if (__log.isDebugEnabled()) {
                __log.debug("InvokeInternal event for mexid " + we.getMexId());
            }

            enqueueTransaction(new Callable<Void>() {
                public Void call() {
                    _contexts.scheduler.jobCompleted(jobInfo.jobName);
                    MessageExchangeDAO mexdao = loadMexDao(we.getMexId());
                    invokeProcess(mexdao);
                    return null;
                }
            });

        } else /* instance-level events */{
            enqueueInstanceTransaction(we.getIID(), new Runnable() {
                public void run() {
                    _contexts.scheduler.jobCompleted(jobInfo.jobName);
                    execInstanceEvent(we);
                }

            });
        }

    }

    /**
     * Enqueue a transaction for execution by the engine.
     * 
     * @param tx
     *            the transaction
     */
    <T> Future<T> enqueueTransaction(final Callable<T> tx) {
        // We have to wrap our transaction to make sure that we are hydrated when the transaction runs.
        return _server.enqueueTransaction(new ProcessCallable<T>(tx));
    }

    private void execInstanceEvent(WorkEvent we) {
        BpelInstanceWorker worker = _instanceWorkerCache.get(we.getIID());
        assert worker.isWorkerThread();

        ProcessInstanceDAO instanceDAO = getProcessDAO().getInstance(we.getIID());
        if (instanceDAO == null) {
            if (__log.isDebugEnabled()) {
                __log.debug("handleWorkEvent: no ProcessInstance found with iid " + we.getIID() + "; ignoring.");
            }
            return;
        }

        BpelRuntimeContextImpl brc = new BpelRuntimeContextImpl(worker, instanceDAO, null, null);
        switch (we.getType()) {
        case TIMER:
            if (__log.isDebugEnabled()) {
                __log.debug("handleWorkEvent: TimerWork event for process instance " + brc);
            }
            brc.timerEvent(we.getChannel());
            break;
        case RESUME:
            if (__log.isDebugEnabled()) {
                __log.debug("handleWorkEvent: ResumeWork event for iid " + we.getIID());
            }
            brc.execute();
            break;
        case PARTNER_RESPONSE:
            if (__log.isDebugEnabled()) {
                __log.debug("InvokeResponse event for iid " + we.getIID());
            }
            brc.injectPartnerResponse(we.getMexId(), we.getChannel());
            brc.execute();
            break;
        case MATCHER:
            if (__log.isDebugEnabled()) {
                __log.debug("Matcher event for iid " + we.getIID());
            }
            brc.matcherEvent(we.getCorrelatorId(), we.getCorrelationKey());
        }
    }

    MessageExchangeDAO loadMexDao(String mexId) {
        return isInMemory() ? _inMemDao.getConnection().getMessageExchange(mexId) : _contexts.dao.getConnection()
                .getMessageExchange(mexId);
    }

    private void setRoles(OProcess oprocess) {
        _partnerRoles = new HashMap<OPartnerLink, PartnerLinkPartnerRoleImpl>();
        _myRoles = new HashMap<OPartnerLink, PartnerLinkMyRoleImpl>();
        _endpointToMyRoleMap = new HashMap<Endpoint, PartnerLinkMyRoleImpl>();

        // Create myRole endpoint name mapping (from deployment descriptor)
        HashMap<OPartnerLink, Endpoint> myRoleEndpoints = new HashMap<OPartnerLink, Endpoint>();
        for (Map.Entry<String, Endpoint> provide : _pconf.getProvideEndpoints().entrySet()) {
            OPartnerLink plink = oprocess.getPartnerLink(provide.getKey());
            if (plink == null) {
                String errmsg = "Error in deployment descriptor for process " + _pid + "; reference to unknown partner link "
                        + provide.getKey();
                __log.error(errmsg);
                throw new BpelEngineException(errmsg);
            }
            myRoleEndpoints.put(plink, provide.getValue());
        }

        // Create partnerRole initial value mapping
        for (Map.Entry<String, Endpoint> invoke : _pconf.getInvokeEndpoints().entrySet()) {
            OPartnerLink plink = oprocess.getPartnerLink(invoke.getKey());
            if (plink == null) {
                String errmsg = "Error in deployment descriptor for process " + _pid + "; reference to unknown partner link "
                        + invoke.getKey();
                __log.error(errmsg);
                throw new BpelEngineException(errmsg);
            }
            __log.debug("Processing <invoke> element for process " + _pid + ": partnerlink " + invoke.getKey() + " --> "
                    + invoke.getValue());
        }

        for (OPartnerLink pl : oprocess.getAllPartnerLinks()) {
            if (pl.hasMyRole()) {
                Endpoint endpoint = myRoleEndpoints.get(pl);
                if (endpoint == null)
                    throw new IllegalArgumentException("No service name for myRole plink " + pl.getName());
                PartnerLinkMyRoleImpl myRole = new PartnerLinkMyRoleImpl(this, pl, endpoint);
                _myRoles.put(pl, myRole);
                _endpointToMyRoleMap.put(endpoint, myRole);
            }

            if (pl.hasPartnerRole()) {
                PartnerLinkPartnerRoleImpl partnerRole = new PartnerLinkPartnerRoleImpl(this, pl, _pconf.getInvokeEndpoints().get(
                        pl.getName()));
                _partnerRoles.put(pl, partnerRole);
            }
        }
    }

    ProcessDAO getProcessDAO() {
        return isInMemory() ? _inMemDao.getConnection().getProcess(_pid) : _contexts.dao.getConnection().getProcess(_pid);
    }

    static String genCorrelatorId(OPartnerLink plink, String opName) {
        return plink.getId() + "." + opName;
    }

    /**
     * De-serialize the compiled process representation from a stream.
     * 
     * @param is
     *            input stream
     * @return process information from configuration database
     */
    private OProcess deserializeCompiledProcess(InputStream is) throws Exception {
        OProcess compiledProcess;
        Serializer ofh = new Serializer(is);
        compiledProcess = ofh.readOProcess();
        return compiledProcess;
    }

    /**
     * Get all the services that are implemented by this process.
     * 
     * @return list of qualified names corresponding to the myroles.
     */
    public Set<Endpoint> getServiceNames() {
        Set<Endpoint> endpoints = new HashSet<Endpoint>();
        for (Endpoint provide : _pconf.getProvideEndpoints().values()) {
            endpoints.add(provide);
        }
        return endpoints;
    }

    void activate(Contexts contexts) {
        _contexts = contexts;
        _debugger = new DebuggerSupport(this);

        __log.debug("Activating " + _pid);
        // Activate all the my-role endpoints.
        for (Map.Entry<String, Endpoint> entry : _pconf.getProvideEndpoints().entrySet()) {
            EndpointReference initialEPR = _contexts.bindingContext.activateMyRoleEndpoint(_pid, entry.getValue());
            __log.debug("Activated " + _pid + " myrole " + entry.getKey() + ": EPR is " + initialEPR);
            _myEprs.put(entry.getValue(), initialEPR);
        }
        __log.debug("Activated " + _pid);

        markused();
    }

    void deactivate() {
        // Deactivate all the my-role endpoints.
        for (Endpoint endpoint : _myEprs.keySet())
            _contexts.bindingContext.deactivateMyRoleEndpoint(endpoint);

        // TODO Deactivate all the partner-role channels
    }

    EndpointReference getInitialPartnerRoleEPR(OPartnerLink link) {
        _hydrationLatch.latch(1);
        try {
            PartnerLinkPartnerRoleImpl prole = _partnerRoles.get(link);
            if (prole == null)
                throw new IllegalStateException("Unknown partner link " + link);
            return prole.getInitialEPR();
        } finally {
            _hydrationLatch.release(1);
        }
    }

    Endpoint getInitialPartnerRoleEndpoint(OPartnerLink link) {
        _hydrationLatch.latch(1);
        try {
            PartnerLinkPartnerRoleImpl prole = _partnerRoles.get(link);
            if (prole == null)
                throw new IllegalStateException("Unknown partner link " + link);
            return prole._initialPartner;
        } finally {
            _hydrationLatch.release(1);
        }
    }

    EndpointReference getInitialMyRoleEPR(OPartnerLink link) {
        _hydrationLatch.latch(1);
        try {
            PartnerLinkMyRoleImpl myRole = _myRoles.get(link);
            if (myRole == null)
                throw new IllegalStateException("Unknown partner link " + link);
            return myRole.getInitialEPR();
        } finally {
            _hydrationLatch.release(1);
        }
    }

    QName getPID() {
        return _pid;
    }

    PartnerRoleChannel getPartnerRoleChannel(OPartnerLink partnerLink) {
        _hydrationLatch.latch(1);
        try {
            PartnerLinkPartnerRoleImpl prole = _partnerRoles.get(partnerLink);
            if (prole == null)
                throw new IllegalStateException("Unknown partner link " + partnerLink);
            return prole._channel;
        } finally {
            _hydrationLatch.release(1);
        }
    }

    public void saveEvent(ProcessInstanceEvent event, ProcessInstanceDAO instanceDao) {
        markused();
        List<String> scopeNames = null;
        if (event instanceof ScopeEvent) {
            scopeNames = ((ScopeEvent) event).getParentScopesNames();
        }

        boolean enabled = _pconf.isEventEnabled(scopeNames, event.getType());
        if (enabled) {
            if (instanceDao != null)
                saveInstanceEvent(event, instanceDao);
            else
                __log.debug("Couldn't find instance to save event, no event generated!");
        }
    }

    void saveInstanceEvent(ProcessInstanceEvent event, ProcessInstanceDAO instanceDao) {
        instanceDao.insertBpelEvent(event);
    }

    /**
     * Ask the process to dehydrate.
     */
    void dehydrate() {
        _hydrationLatch.latch(0);

        try {
            // We don't actually need to do anything, the latch will run the doDehydrate method
            // when necessary..
        } finally {
            _hydrationLatch.release(0);
        }

    }

    void hydrate() {
        _hydrationLatch.latch(1);

        try {
            // We don't actually need to do anything, the latch will run the doHydrate method
            // when necessary..
        } finally {
            _hydrationLatch.release(1);
        }
    }

    OProcess getOProcess() {
        _hydrationLatch.latch(1);
        try {
            return _oprocess;
        } finally {
            _hydrationLatch.release(1);
        }
    }

    private MyRoleMessageExchangeImpl newMyRoleMex(InvocationStyle istyle, String mexId, QName target, OPartnerLink oplink,
            Operation operation) {
        MyRoleMessageExchangeImpl mex;
        switch (istyle) {
        case RELIABLE:
            mex = new ReliableMyRoleMessageExchangeImpl(this, mexId, oplink, operation, target);
            break;
        case TRANSACTED:
            mex = new TransactedMyRoleMessageExchangeImpl(this, mexId, oplink, operation, target);
            break;
        case UNRELIABLE:
            mex = new UnreliableMyRoleMessageExchangeImpl(this, mexId, oplink, operation, target);
            break;
        default:
            throw new AssertionError("Unexpected invocation style: " + istyle);

        }

        registerMyRoleMex(mex);
        return mex;
    }

    MyRoleMessageExchangeImpl recreateMyRoleMex(MessageExchangeDAO mexdao) {
        InvocationStyle istyle = InvocationStyle.valueOf(mexdao.getInvocationStyle());

        _hydrationLatch.latch(1);
        try {
            OPartnerLink plink = (OPartnerLink) _oprocess.getChild(mexdao.getPartnerLinkModelId());
            if (plink == null) {
                String errmsg = __msgs.msgDbConsistencyError("MexDao #" + mexdao.getMessageExchangeId()
                        + " referenced unknown pLinkModelId " + mexdao.getPartnerLinkModelId());
                __log.error(errmsg);
                throw new BpelEngineException(errmsg);
            }

            Operation op = plink.getMyRoleOperation(mexdao.getOperation());
            if (op == null) {
                String errmsg = __msgs.msgDbConsistencyError("MexDao #" + mexdao.getMessageExchangeId()
                        + " referenced unknown operation " + mexdao.getOperation());
                __log.error(errmsg);
                throw new BpelEngineException(errmsg);
            }

            PartnerLinkMyRoleImpl myRole = _myRoles.get(plink);
            if (myRole == null) {
                String errmsg = __msgs.msgDbConsistencyError("MexDao #" + mexdao.getMessageExchangeId()
                        + " referenced non-existant myrole");
                __log.error(errmsg);
                throw new BpelEngineException(errmsg);
            }

            MyRoleMessageExchangeImpl mex = newMyRoleMex(istyle, mexdao.getMessageExchangeId(), myRole._endpoint.serviceName,
                    plink, op);
            mex.load(mexdao);
            return mex;
        } finally {
            _hydrationLatch.release(1);
        }
    }

    PartnerRoleMessageExchangeImpl createPartnerRoleMex(MessageExchangeDAO mexdao) {

        _hydrationLatch.latch(1);
        try {
            OPartnerLink plink = (OPartnerLink) _oprocess.getChild(mexdao.getPartnerLinkModelId());
            PartnerLinkPartnerRoleImpl prole = _partnerRoles.get(plink);
            return prole.createPartnerRoleMex(mexdao);
        } finally {
            _hydrationLatch.release(1);
        }

    }

    Set<InvocationStyle> getSupportedInvocationStyle(QName serviceId) {
        return _invocationStyles;
    }

    /**
     * Find the partner-link-my-role that corresponds to the given service name.
     * 
     * @param serviceName
     *            name of service
     * @return corresponding {@link PartnerLinkMyRoleImpl}
     */
    private PartnerLinkMyRoleImpl getPartnerLinkForService(QName serviceName) {
        assert _hydrationLatch.isLatched(1);

        PartnerLinkMyRoleImpl target = null;
        for (Endpoint endpoint : _endpointToMyRoleMap.keySet()) {
            if (endpoint.serviceName.equals(serviceName))
                target = _endpointToMyRoleMap.get(endpoint);
        }

        return target;

    }

    /**
     * Used by {@link BpelRuntimeContextImpl} constructor. Should only be called from latched context.
     * 
     * @return
     */
    ReplacementMap getReplacementMap() {
        assert _hydrationLatch.isLatched(1);
        return _replacementMap;
    }

    public boolean isInMemory() {
        return _pconf.isTransient();
    }

    public long getLastUsed() {
        return _lastUsed;
    }

    /**
     * Get a hint as to whether this process is hydrated. Note this is only a hint, since things could change.
     */
    public boolean hintIsHydrated() {
        return _oprocess != null;
    }

    /** Keep track of the time the process was last used. */
    private final void markused() {
        _lastUsed = System.currentTimeMillis();
    }

    /**
     * If necessary, create an object in the data store to represent the process. We'll re-use an existing object if it already
     * exists and matches the GUID.
     */
    private void bounceProcessDAO(BpelDAOConnection conn, final QName pid, final long version, final OProcess oprocess) {
        __log.debug("Creating process DAO for " + pid + " (guid=" + oprocess.guid + ")");
        try {
            boolean create = true;
            ProcessDAO old = conn.getProcess(pid);
            if (old != null) {
                __log.debug("Found ProcessDAO for " + pid + " with GUID " + old.getGuid());
                if (oprocess.guid == null) {
                    // No guid, old version assume its good
                    create = false;
                } else {
                    if (old.getGuid().equals(oprocess.guid)) {
                        // Guids match, no need to create
                        create = false;
                    } else {
                        // GUIDS dont match, delete and create new
                        String errmsg = "ProcessDAO GUID " + old.getGuid() + " does not match " + oprocess.guid + "; replacing.";
                        __log.debug(errmsg);
                        old.delete();
                    }
                }
            }

            if (create) {
                ProcessDAO newDao = conn.createProcess(pid, oprocess.getQName(), oprocess.guid, (int) version);
                for (String correlator : oprocess.getCorrelators()) {
                    newDao.addCorrelator(correlator);
                }
            }
        } catch (BpelEngineException ex) {
            throw ex;
        } catch (Exception dce) {
            __log.error("DbError", dce);
            throw new BpelEngineException("DbError", dce);
        }
    }

    MessageExchangeDAO createMessageExchange(String mexId, final char dir) {
        if (isInMemory()) {
            return _inMemDao.getConnection().createMessageExchange(mexId, dir);
        } else {
            return _contexts.dao.getConnection().createMessageExchange(mexId, dir);
        }
    }

    MessageExchangeDAO getInMemMexDAO(String mexId) {
        return _inMemDao.getConnection().getMessageExchange(mexId);
    }

    /**
     * Schedule process-level work. This method defers to the server to do the scheduling and wraps the {@link Runnable} in a
     * try-finally block that ensures that the process is hydrated.
     * 
     * @param runnable
     */
    void scheduleRunnable(final Runnable runnable) {
        if (__log.isDebugEnabled())
            __log.debug("schedulingRunnable for process " + _pid + ": " + runnable);

        _server.scheduleRunnable(new ProcessRunnable(runnable));
    }

    void enqueueRunnable(BpelInstanceWorker worker) {
        if (__log.isDebugEnabled())
            __log.debug("enqueuRunnable for process " + _pid + ": " + worker);

        _server.enqueueRunnable(new ProcessRunnable(worker));
    }

    MyRoleMessageExchange createNewMyRoleMex(final InvocationStyle istyle, final QName targetService, final String operation,
            final String clientKey) {

        final String mexId = new GUID().toString();
        _hydrationLatch.latch(1);
        try {

            final PartnerLinkMyRoleImpl target = getPartnerLinkForService(targetService);
            if (target == null)
                throw new BpelEngineException("NoSuchService: " + targetService);
            final Operation op = target._plinkDef.getMyRoleOperation(operation);
            if (op == null)
                throw new BpelEngineException("NoSuchOperation: " + operation);

            return newMyRoleMex(istyle, mexId, target._endpoint.serviceName, target._plinkDef, op);

        } finally {
            _hydrationLatch.release(1);
        }
    }

    void registerMyRoleMex(MyRoleMessageExchangeImpl mymex) {
        _mexStateListeners.add(new WeakReference<MyRoleMessageExchangeImpl>(mymex));
    }

    void unregisterMyRoleMex(MyRoleMessageExchangeImpl mymex) {
        ArrayList<WeakReference<MyRoleMessageExchangeImpl>> needsRemoval = new ArrayList<WeakReference<MyRoleMessageExchangeImpl>>();
        for (WeakReference<MyRoleMessageExchangeImpl> wref : _mexStateListeners) {
            MyRoleMessageExchangeImpl mex = wref.get();
            if (mex == null || mex == mymex)
                needsRemoval.add(wref);
        }
        _mexStateListeners.removeAll(needsRemoval);

    }

    void fireMexStateEvent(MessageExchangeDAO mexdao, Status old, Status news) {
        // TODO: force a myrole mex to be created if it is not in cache.

        if (old != news)
            for (WeakReference<MyRoleMessageExchangeImpl> wr : _mexStateListeners) {
                MyRoleMessageExchangeImpl mymex = wr.get();
                if (mymex != null && mymex.getMessageExchangeId() != null)
                    mymex.onStateChanged(mexdao, old, news);
            }

    }

    class ProcessRunnable implements Runnable {
        Runnable _work;

        ProcessRunnable(Runnable work) {
            _work = work;
        }

        public void run() {
            _hydrationLatch.latch(1);
            try {
                _work.run();
            } finally {
                _hydrationLatch.release(1);
            }

        }

    }

    class ProcessCallable<T> implements Callable<T> {
        Callable<T> _work;

        ProcessCallable(Callable<T> work) {
            _work = work;
        }

        public T call() throws Exception {
            _hydrationLatch.latch(1);
            try {
                return _work.call();
            } finally {
                _hydrationLatch.release(1);
            }

        }

    }

    class HydrationLatch extends NStateLatch {

        HydrationLatch() {
            super(new Runnable[2]);
            _transitions[0] = new Runnable() {
                public void run() {
                    doDehydrate();
                }
            };

            _transitions[1] = new Runnable() {
                public void run() {
                    doHydrate();
                }
            };

        }

        private void doDehydrate() {
            _oprocess = null;
            _partnerRoles = null;
            _myRoles = null;
            _endpointToMyRoleMap = null;
            _replacementMap = null;
            _expLangRuntimeRegistry = null;
        }

        private void doHydrate() {
            markused();
            __log.debug("Rehydrating process " + _pconf.getProcessId());
            try {
                _oprocess = deserializeCompiledProcess(_pconf.getCBPInputStream());
            } catch (Exception e) {
                String errmsg = "Error reloading compiled process " + _pid + "; the file appears to be corrupted.";
                __log.error(errmsg);
                throw new BpelEngineException(errmsg, e);
            }

            _replacementMap = new ReplacementMapImpl(_oprocess);

            // Create an expression language registry for this process
            ExpressionLanguageRuntimeRegistry elangRegistry = new ExpressionLanguageRuntimeRegistry();
            for (OExpressionLanguage elang : _oprocess.expressionLanguages) {
                try {
                    elangRegistry.registerRuntime(elang);
                } catch (ConfigurationException e) {
                    String msg = __msgs.msgExpLangRegistrationError(elang.expressionLanguageUri, elang.properties);
                    __log.error(msg, e);
                    throw new BpelEngineException(msg, e);
                }
            }
            _expLangRuntimeRegistry = elangRegistry;

            setRoles(_oprocess);

            if (!_hydratedOnce) {
                for (PartnerLinkPartnerRoleImpl prole : _partnerRoles.values()) {
                    PartnerRoleChannel channel = _contexts.bindingContext.createPartnerRoleChannel(_pid,
                            prole._plinkDef.partnerRolePortType, prole._initialPartner);
                    prole._channel = channel;
                    _partnerChannels.put(prole._initialPartner, prole._channel);
                    EndpointReference epr = channel.getInitialEndpointReference();
                    if (epr != null) {
                        prole._initialEPR = epr;
                        _partnerEprs.put(prole._initialPartner, epr);
                    }
                    __log.debug("Activated " + _pid + " partnerrole " + prole.getPartnerLinkName() + ": EPR is "
                            + prole._initialEPR);
                }
                _hydratedOnce = true;
            }

            for (PartnerLinkMyRoleImpl myrole : _myRoles.values()) {
                myrole._initialEPR = _myEprs.get(myrole._endpoint);
            }

            for (PartnerLinkPartnerRoleImpl prole : _partnerRoles.values()) {
                prole._channel = _partnerChannels.get(prole._initialPartner);
                if (_partnerEprs.get(prole._initialPartner) != null) {
                    prole._initialEPR = _partnerEprs.get(prole._initialPartner);
                }
            }

            if (isInMemory()) {
                bounceProcessDAO(_inMemDao.getConnection(), _pid, _pconf.getVersion(), _oprocess);
            } else if (_contexts.isTransacted()) {
                // If we have a transaction, we do this in the current transaction.
                bounceProcessDAO(_contexts.dao.getConnection(), _pid, _pconf.getVersion(), _oprocess);
            } else {
                // If we do not have a transaction we need to create one.
                try {
                    _contexts.execTransaction(new Callable<Object>() {
                        public Object call() throws Exception {
                            bounceProcessDAO(_contexts.dao.getConnection(), _pid, _pconf.getVersion(), _oprocess);
                            return null;
                        }
                    });
                } catch (Exception ex) {
                    String errmsg = "DbError";
                    __log.error(errmsg, ex);
                    throw new BpelEngineException(errmsg, ex);
                }
            }
        }

    }

    /**
     * Invoke a partner via the integration layer.
     * 
     * @param mexDao
     * @param brc
     */
    void invokeIL(MessageExchangeDAO mexDao) {
        _hydrationLatch.latch(1);
        try {
            OPartnerLink oplink = (OPartnerLink) _oprocess.getChild(mexDao.getPartnerLinkModelId());
            PartnerLinkPartnerRoleImpl partnerRole = _partnerRoles.get(oplink);
            partnerRole.invokeIL(mexDao);
        } finally {
            _hydrationLatch.release(1);
        }
    }

}