/*
 * Copyright (c) 1997, 2010, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.xml.internal.ws.server;

import com.sun.istack.internal.NotNull;
import com.sun.istack.internal.Nullable;
import com.sun.xml.internal.ws.api.SOAPVersion;
import com.sun.xml.internal.ws.api.WSBinding;
import com.sun.xml.internal.ws.api.addressing.AddressingVersion;
import com.sun.xml.internal.ws.api.addressing.WSEndpointReference;
import com.sun.xml.internal.ws.api.message.Message;
import com.sun.xml.internal.ws.api.message.Packet;
import com.sun.xml.internal.ws.api.model.SEIModel;
import com.sun.xml.internal.ws.api.model.wsdl.WSDLPort;
import com.sun.xml.internal.ws.api.pipe.Codec;
import com.sun.xml.internal.ws.api.pipe.Engine;
import com.sun.xml.internal.ws.api.pipe.Fiber;
import com.sun.xml.internal.ws.api.pipe.FiberContextSwitchInterceptor;
import com.sun.xml.internal.ws.api.pipe.ServerPipeAssemblerContext;
import com.sun.xml.internal.ws.api.pipe.ServerTubeAssemblerContext;
import com.sun.xml.internal.ws.api.pipe.Tube;
import com.sun.xml.internal.ws.api.pipe.TubeCloner;
import com.sun.xml.internal.ws.api.pipe.TubelineAssembler;
import com.sun.xml.internal.ws.api.pipe.TubelineAssemblerFactory;
import com.sun.xml.internal.ws.api.server.*;
import com.sun.xml.internal.ws.fault.SOAPFaultBuilder;
import com.sun.xml.internal.ws.model.wsdl.WSDLProperties;
import com.sun.xml.internal.ws.model.wsdl.WSDLPortImpl;
import com.sun.xml.internal.ws.resources.HandlerMessages;
import com.sun.xml.internal.ws.util.Pool;
import com.sun.xml.internal.ws.util.ServiceFinder;
import com.sun.xml.internal.ws.util.Pool.TubePool;
import com.sun.xml.internal.ws.policy.PolicyMap;
import com.sun.xml.internal.ws.wsdl.OperationDispatcher;
import com.sun.xml.internal.ws.addressing.EPRSDDocumentFilter;
import com.sun.xml.internal.ws.addressing.WSEPRExtension;
import com.sun.xml.internal.stream.buffer.XMLStreamBuffer;
import com.sun.org.glassfish.gmbal.ManagedObjectManager;
import org.w3c.dom.Element;

import javax.annotation.PreDestroy;
import javax.xml.namespace.QName;
import javax.xml.ws.EndpointReference;
import javax.xml.ws.WebServiceException;
import javax.xml.ws.handler.Handler;
import javax.xml.stream.XMLStreamException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link WSEndpoint} implementation.
 *
 * @author Kohsuke Kawaguchi
 * @author Jitendra Kotamraju
 */
public final class WSEndpointImpl<T> extends WSEndpoint<T> {
    private final @NotNull QName serviceName;
    private final @NotNull QName portName;
    private final WSBinding binding;
    private final SEIModel seiModel;
    private final @NotNull Container container;
    private final WSDLPort port;

    private final Tube masterTubeline;
    private final ServiceDefinitionImpl serviceDef;
    private final SOAPVersion soapVersion;
    private final Engine engine;
    private final @NotNull Codec masterCodec;
    private final @NotNull PolicyMap endpointPolicy;
    private final Pool<Tube> tubePool;
    private final OperationDispatcher operationDispatcher;
    private final @NotNull ManagedObjectManager managedObjectManager;
    private       boolean managedObjectManagerClosed = false;
    private final @NotNull ServerTubeAssemblerContext context;

    private Map<QName, WSEndpointReference.EPRExtension> endpointReferenceExtensions = new HashMap<QName, WSEndpointReference.EPRExtension>();
    /**
     * Set to true once we start shutting down this endpoint.
     * Used to avoid running the clean up processing twice.
     *
     * @see #dispose()
     */
    private boolean disposed;

    private final Class<T> implementationClass;
    private final @Nullable WSDLProperties wsdlProperties;
    private final Set<EndpointComponent> componentRegistry = new LinkedHashSet<EndpointComponent>();

    WSEndpointImpl(@NotNull QName serviceName, @NotNull QName portName, WSBinding binding,
                   Container container, SEIModel seiModel, WSDLPort port,
                   Class<T> implementationClass,
                   @Nullable ServiceDefinitionImpl serviceDef,
                   InvokerTube terminalTube, boolean isSynchronous,
                   PolicyMap endpointPolicy) {
        this.serviceName = serviceName;
        this.portName = portName;
        this.binding = binding;
        this.soapVersion = binding.getSOAPVersion();
        this.container = container;
        this.port = port;
        this.implementationClass = implementationClass;
        this.serviceDef = serviceDef;
        this.seiModel = seiModel;
        this.endpointPolicy = endpointPolicy;

        this.managedObjectManager =
            new MonitorRootService(this).createManagedObjectManager(this);

        if (serviceDef != null) {
            serviceDef.setOwner(this);
        }

        TubelineAssembler assembler = TubelineAssemblerFactory.create(
                Thread.currentThread().getContextClassLoader(), binding.getBindingId(), container);
        assert assembler!=null;

        this.operationDispatcher = (port == null) ? null : new OperationDispatcher(port, binding, seiModel);

        context = new ServerPipeAssemblerContext(seiModel, port, this, terminalTube, isSynchronous);
        this.masterTubeline = assembler.createServer(context);

        Codec c = context.getCodec();
        if(c instanceof EndpointAwareCodec) {
            // create a copy to avoid sharing the codec between multiple endpoints
            c = c.copy();
            ((EndpointAwareCodec)c).setEndpoint(this);
        }
        this.masterCodec = c;

        tubePool = new TubePool(masterTubeline);
        terminalTube.setEndpoint(this);
        engine = new Engine(toString());
        wsdlProperties = (port==null) ? null : new WSDLProperties(port);

        Map<QName, WSEndpointReference.EPRExtension> eprExtensions = new HashMap<QName, WSEndpointReference.EPRExtension>();
        try {
            if (port != null) {
                //gather EPR extrensions from WSDL Model
                WSEndpointReference wsdlEpr = ((WSDLPortImpl) port).getEPR();
                if (wsdlEpr != null) {
                    for (WSEndpointReference.EPRExtension extnEl : wsdlEpr.getEPRExtensions()) {
                        eprExtensions.put(extnEl.getQName(), extnEl);
                    }
                }
            }

            EndpointReferenceExtensionContributor[] eprExtnContributors = ServiceFinder.find(EndpointReferenceExtensionContributor.class).toArray();
            for(EndpointReferenceExtensionContributor eprExtnContributor :eprExtnContributors) {
                WSEndpointReference.EPRExtension wsdlEPRExtn = eprExtensions.remove(eprExtnContributor.getQName());
                    WSEndpointReference.EPRExtension endpointEprExtn = eprExtnContributor.getEPRExtension(this,wsdlEPRExtn);
                    if (endpointEprExtn != null) {
                        eprExtensions.put(endpointEprExtn.getQName(), endpointEprExtn);
                    }
            }
            for (WSEndpointReference.EPRExtension extn : eprExtensions.values()) {
                endpointReferenceExtensions.put(extn.getQName(), new WSEPRExtension(
                        XMLStreamBuffer.createNewBufferFromXMLStreamReader(extn.readAsXMLStreamReader()),extn.getQName()));
            }
        } catch (XMLStreamException ex) {
            throw new WebServiceException(ex);
        }
        if(!eprExtensions.isEmpty()) {
            serviceDef.addFilter(new EPRSDDocumentFilter(this));
        }

    }

    public Collection<WSEndpointReference.EPRExtension> getEndpointReferenceExtensions() {
        return endpointReferenceExtensions.values();
    }
    /**
     * Nullable when there is no associated WSDL Model
     * @return
     */
    public @Nullable OperationDispatcher getOperationDispatcher() {
        return operationDispatcher;
    }

    public PolicyMap getPolicyMap() {
            return endpointPolicy;
    }

    public @NotNull Class<T> getImplementationClass() {
        return implementationClass;
    }

    public @NotNull WSBinding getBinding() {
        return binding;
    }

    public @NotNull Container getContainer() {
        return container;
    }

    public WSDLPort getPort() {
        return port;
    }

    @Override
    public @Nullable SEIModel getSEIModel() {
        return seiModel;
    }

    public void setExecutor(Executor exec) {
        engine.setExecutor(exec);
    }

    public void schedule(final Packet request, final CompletionCallback callback, FiberContextSwitchInterceptor interceptor) {
        processAsync(request, callback, interceptor, true);
    }

    private void processAsync(final Packet request, final CompletionCallback callback, FiberContextSwitchInterceptor interceptor, boolean schedule) {
        request.endpoint = WSEndpointImpl.this;
        if (wsdlProperties != null) {
            request.addSatellite(wsdlProperties);
        }
        Fiber fiber = engine.createFiber();
        if (interceptor != null) {
            fiber.addInterceptor(interceptor);
        }
        final Tube tube = tubePool.take();

        Fiber.CompletionCallback cbak = new Fiber.CompletionCallback() {
            public void onCompletion(@NotNull Packet response) {
                tubePool.recycle(tube);
                if (callback!=null) {
                    callback.onCompletion(response);
                }
            }

            public void onCompletion(@NotNull Throwable error) {
                // let's not reuse tubes as they might be in a wrong state, so not
                // calling tubePool.recycle()
                error.printStackTrace();
                // Convert all runtime exceptions to Packet so that transport doesn't
                // have to worry about converting to wire message
                // TODO XML/HTTP binding
                Message faultMsg = SOAPFaultBuilder.createSOAPFaultMessage(
                        soapVersion, null, error);
                Packet response = request.createServerResponse(faultMsg, request.endpoint.getPort(), null,
                        request.endpoint.getBinding());
                if (callback!=null) {
                    callback.onCompletion(response);
                }
            }
        };
        if (schedule) {
            fiber.start(tube, request, cbak);
        } else {
            fiber.runAsync(tube, request, cbak);
        }
    }

    @Override
    public void process(final Packet request, final CompletionCallback callback, FiberContextSwitchInterceptor interceptor) {
        processAsync(request, callback, interceptor, false);
    }

    public @NotNull PipeHead createPipeHead() {
        return new PipeHead() {
            private final Tube tube = TubeCloner.clone(masterTubeline);

            public @NotNull Packet process(Packet request, WebServiceContextDelegate wscd, TransportBackChannel tbc) {
                request.webServiceContextDelegate = wscd;
                request.transportBackChannel = tbc;
                request.endpoint = WSEndpointImpl.this;
                if (wsdlProperties != null) {
                    request.addSatellite(wsdlProperties);
                }
                Fiber fiber = engine.createFiber();
                Packet response;
                try {
                    response = fiber.runSync(tube,request);
                } catch (RuntimeException re) {
                    // Catch all runtime exceptions so that transport doesn't
                    // have to worry about converting to wire message
                    // TODO XML/HTTP binding
                    re.printStackTrace();
                    Message faultMsg = SOAPFaultBuilder.createSOAPFaultMessage(
                            soapVersion, null, re);
                    response = request.createServerResponse(faultMsg, request.endpoint.getPort(), null, request.endpoint.getBinding());
                }
                return response;
            }
        };
    }

    public synchronized void dispose() {
        if(disposed)
            return;
        disposed = true;

        masterTubeline.preDestroy();

        for (Handler handler : binding.getHandlerChain()) {
            for (Method method : handler.getClass().getMethods()) {
                if (method.getAnnotation(PreDestroy.class) == null) {
                    continue;
                }
                try {
                    method.invoke(handler);
                } catch (Exception e) {
                    logger.log(Level.WARNING, HandlerMessages.HANDLER_PREDESTROY_IGNORE(e.getMessage()), e);
                }
                break;
            }
        }
        closeManagedObjectManager();
    }

    public ServiceDefinitionImpl getServiceDefinition() {
        return serviceDef;
    }

    public Set<EndpointComponent> getComponentRegistry() {
        return componentRegistry;
    }

    private static final Logger logger = Logger.getLogger(
        com.sun.xml.internal.ws.util.Constants.LoggingDomain + ".server.endpoint");

    public <T extends EndpointReference> T getEndpointReference(Class<T>
            clazz, String address, String wsdlAddress, Element... referenceParameters) {
        List<Element> refParams = null;
        if (referenceParameters != null) {
            refParams = Arrays.asList(referenceParameters);
        }
        return getEndpointReference(clazz, address, wsdlAddress, null, refParams);
    }

    public <T extends EndpointReference> T getEndpointReference(Class<T>
            clazz, String address, String wsdlAddress, List<Element> metadata, List<Element> referenceParameters) {
        QName portType = null;
        if (port != null) {
            portType = port.getBinding().getPortTypeName();
        }

        AddressingVersion av = AddressingVersion.fromSpecClass(clazz);
        return new WSEndpointReference(
                    av, address, serviceName, portName, portType, metadata, wsdlAddress, referenceParameters,endpointReferenceExtensions.values(), null).toSpec(clazz);

    }

    public @NotNull QName getPortName() {
        return portName;
    }


    public @NotNull Codec createCodec() {
        return masterCodec.copy();
    }

    public @NotNull QName getServiceName() {
        return serviceName;
    }

    public @NotNull ManagedObjectManager getManagedObjectManager() {
        return managedObjectManager;
    }

    // This can be called independently of WSEndpoint.dispose.
    // Example: the WSCM framework calls this before dispose.
    public void closeManagedObjectManager() {
        if (managedObjectManagerClosed == true) {
            return;
        }
        MonitorBase.closeMOM(managedObjectManager);
        managedObjectManagerClosed = true;
    }

    public @NotNull ServerTubeAssemblerContext getAssemblerContext() {
        return context;
    }
}
