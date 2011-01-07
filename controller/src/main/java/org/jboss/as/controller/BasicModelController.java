/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.controller;

import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.as.controller.registry.OperationRegistry;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;

/**
 * A basic model controller.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public class BasicModelController implements ModelController {

    private static final Logger log = Logger.getLogger("org.jboss.as.controller");

    private static final String[] NO_STRINGS = new String[0];
    private final OperationRegistry registry = OperationRegistry.create();
    private final ModelNode model;
    private final NewConfigurationPersister configurationPersister;

    /**
     * Construct a new instance.
     *
     * @param configurationPersister the configuration persister to use to store changes
     */
    protected BasicModelController(final NewConfigurationPersister configurationPersister) {
        model = new ModelNode().setEmptyObject();
        this.configurationPersister = configurationPersister;
    }

    /** {@inheritDoc} */
    public void registerOperationHandler(final PathAddress address, final String name, final OperationHandler handler) {
        registry.register(address, name, handler);
    }

    /**
     * Get the operation handler for an address and name.
     *
     * @param address the address
     * @param name the name
     * @return the operation handler
     */
    protected OperationHandler getHandler(final PathAddress address, final String name) {
        return registry.getHandler(address, name);
    }

    /**
     * Get a failure result from a throwable exception.
     *
     * @param t the exception
     * @return the failure result
     */
    protected ModelNode getFailureResult(Throwable t) {
        final ModelNode node = new ModelNode();
        // todo - define this structure
        node.get("success").set(false);
        do {
            node.get("cause").add(t.getClass().getName(), t.getLocalizedMessage());
            t = t.getCause();
        } while (t != null);
        return node;
    }

    /** {@inheritDoc} */
    public Cancellable execute(final ModelNode operation, final ResultHandler handler) {
        final PathAddress address = PathAddress.pathAddress(operation.get("address"));
        final String operationName = operation.get("operation").asString();
        final OperationHandler operationHandler = registry.getHandler(address, operationName);
        final ModelNode subModel;
        try {
            // todo, this is a bit of a hack - only create a new node if the operation is called "add"
            subModel = address.navigate(model, operationName.equals("add"));
        } catch (NoSuchElementException e) {
            handler.handleResultFragment(NO_STRINGS, getFailureResult(e));
            return Cancellable.NULL;
        }
        final NewOperationContext context = getOperationContext(subModel, operation, operationHandler);
        final ResultHandler persistingHandler = new ResultHandler() {
            public void handleResultFragment(final String[] location, final ModelNode result) {
                handler.handleResultFragment(location, result);
            }

            public void handleResultComplete(final ModelNode compensatingOperation) {
                handler.handleResultComplete(compensatingOperation);
                try {
                    configurationPersister.store(model);
                } catch (ConfigurationPersistenceException e) {
                    log.warnf("Failed to persist configuration change: %s", e);
                }
            }

            public void handleFailed(final ModelNode failureDescription) {
                handler.handleFailed(failureDescription);
            }

            public void handleCancellation() {
                handler.handleCancellation();
            }
        };
        try {
            return doExecute(context, operation, operationHandler, persistingHandler);
        } catch (Throwable t) {
            handler.handleFailed(getFailureResult(t));
            return Cancellable.NULL;
        }
    }

    /**
     * Get the operation context for the operation.  By default, this method creates a basic implementation of
     * {@link NewOperationContext}.
     *
     * @param subModel the submodel affected by the operation
     * @param operation the operation itself
     * @param operationHandler the operation handler which will run the operation
     * @return the operation context
     */
    @SuppressWarnings("unused")
    protected NewOperationContext getOperationContext(final ModelNode subModel, final ModelNode operation, final OperationHandler operationHandler) {
        return new NewOperationContextImpl(this, subModel);
    }

    /**
     * Actually perform this operation.  By default, this method simply calls the {@link OperationHandler#execute(NewOperationContext, ModelNode, ResultHandler)}
     * method, applying the operation to the relevant submodel.  If this method throws an exception, the result handler
     * will automatically be notified.  If the operation completes successfully, any configuration change will be persisted.
     *
     * @param context the context for the operation
     * @param operation the operation itself
     * @param operationHandler the operation handler which will run the operation
     * @param resultHandler the result handler for this operation
     * @return a handle which can be used to asynchronously cancel the operation
     */
    protected Cancellable doExecute(final NewOperationContext context, final ModelNode operation, final OperationHandler operationHandler, final ResultHandler resultHandler) {
        return operationHandler.execute(context, operation, resultHandler);
    }

    /** {@inheritDoc} */
    public ModelNode execute(final ModelNode operation) throws OperationFailedException {
        final AtomicInteger status = new AtomicInteger();
        final ModelNode finalResult = new ModelNode();
        final Cancellable handle = execute(operation, new ResultHandler() {
            public void handleResultFragment(final String[] location, final ModelNode result) {
                synchronized (finalResult) {
                    finalResult.get(location).set(result);
                }
            }

            public void handleResultComplete(final ModelNode compensatingOperation) {
                synchronized (finalResult) {
                    status.set(1);
                    finalResult.notify();
                }
            }

            public void handleFailed(final ModelNode failureDescription) {
                synchronized (finalResult) {
                    status.set(3);
                    finalResult.set(failureDescription);
                    finalResult.notify();
                }
            }

            public void handleCancellation() {
                synchronized (finalResult) {
                    status.set(2);
                    finalResult.notify();
                }
            }
        });
        boolean intr = false;
        try {
            synchronized (finalResult) {
                for (;;) {
                    try {
                        final int s = status.get();
                        switch (s) {
                            case 1: return finalResult;
                            case 2: throw new CancellationException();
                            case 3: throw new OperationFailedException(finalResult);
                        }
                        finalResult.wait();
                    } catch (InterruptedException e) {
                        intr = true;
                        handle.cancel();
                    }
                }
            }
        } finally {
            if (intr) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
