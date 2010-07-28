/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
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

package org.jboss.as.deployment;

import org.jboss.as.deployment.chain.DeploymentChain;
import org.jboss.as.deployment.chain.DeploymentChainProvider;
import org.jboss.as.deployment.chain.DeploymentChainProviderTranslator;
import org.jboss.as.deployment.item.DeploymentItem;
import org.jboss.as.deployment.item.DeploymentItemContext;
import org.jboss.as.deployment.item.DeploymentItemContextImpl;
import org.jboss.as.deployment.module.DeploymentModuleLoader;
import org.jboss.as.deployment.module.DeploymentModuleLoaderProvider;
import org.jboss.as.deployment.module.DeploymentModuleLoaderSelector;
import org.jboss.as.deployment.module.DeploymentModuleLoaderProviderTranslator;
import org.jboss.as.deployment.module.ModuleConfig;
import org.jboss.as.deployment.unit.DeploymentUnitContext;
import org.jboss.as.deployment.unit.DeploymentUnitContextImpl;
import org.jboss.as.deployment.unit.DeploymentUnitProcessingException;
import org.jboss.logging.Logger;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleLoadException;
import org.jboss.msc.inject.PropertyInjector;
import org.jboss.msc.inject.TranslatingInjector;
import org.jboss.msc.service.BatchBuilder;
import org.jboss.msc.service.BatchServiceBuilder;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceRegistryException;
import org.jboss.msc.service.ServiceUtils;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.services.VFSMountService;
import org.jboss.msc.value.Values;
import org.jboss.vfs.TempFileProvider;
import org.jboss.vfs.VFSUtils;
import org.jboss.vfs.VirtualFile;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.jboss.as.deployment.attachment.VirtualFileAttachment.attachVirtualFile;

/**
 * Deployment manager implementation.
 *
 * @author John E. Bailey
 */
public class DeploymentManagerImpl implements DeploymentManager, Service<DeploymentManager> {
    private static final ServiceName MOUNT_SERVICE_NAME = ServiceName.JBOSS.append("mounts");
    private static Logger logger = Logger.getLogger("org.jboss.as.deployment");

    private final ServiceContainer serviceContainer;
    private TempFileProvider tempFileProvider;

    public DeploymentManagerImpl(final ServiceContainer serviceContainer) {
        this.serviceContainer = serviceContainer;
    }

    @Override
    public void start(final StartContext context) throws StartException {
        try {
            tempFileProvider = TempFileProvider.create("mount", Executors.newScheduledThreadPool(2));
        } catch(IOException e) {
            throw new StartException("Failed to create temp file provider", e);
        }
    }

    @Override
    public void stop(final StopContext context) {
        VFSUtils.safeClose(tempFileProvider);
    }

    @Override
    public DeploymentManager getValue() throws IllegalStateException {
        return this;
    }

    @Override
    public final DeploymentResult.Future deploy(final VirtualFile... deploymentRoots) throws DeploymentException {
        final DeploymentResultImpl.FutureImpl future = new DeploymentResultImpl.FutureImpl();
        deployDeploymentServices(future, deploymentRoots);
        return future;
    }

    @Override
    public DeploymentResult deployAndWait(VirtualFile... roots) throws DeploymentException {
        return deploy(roots).getDeploymentResult();
    }

    /**
     * Phase 1 - Initialize Deployment - Mount and deployment service batch
    */
    private void deployDeploymentServices(final DeploymentResultImpl.FutureImpl future, final VirtualFile... deploymentRoots) {
        final List<Deployment> deployments = new ArrayList<Deployment>(deploymentRoots.length);

        // Setup batch
        final ServiceContainer serviceContainer = this.serviceContainer;
        final BatchBuilder batchBuilder = serviceContainer.batchBuilder();

        // Setup deployment listener
        final DeploymentServiceListener deploymentServiceListener = new DeploymentServiceListener(new DeploymentServiceListener.Callback() {
            @Override
            public void run(Map<ServiceName, StartException> serviceFailures, long elapsedTime, int numServices) {
                DeploymentResult.Result result = DeploymentResult.Result.SUCCESS;
                DeploymentException deploymentException = null;
                if(serviceFailures.size() > 0) {
                    result = DeploymentResult.Result.FAILURE;
                    deploymentException = new DeploymentException("Failed to execute deployments.  Not all services started cleanly.");
                }
                future.setDeploymentResult(new DeploymentResultImpl(result, deploymentException, serviceFailures, elapsedTime, numServices));
            }
        });
        batchBuilder.addListener(deploymentServiceListener);

        try {
            for(VirtualFile deploymentRoot : deploymentRoots) {
                final String deploymentName = deploymentRoot.getName();
                if(!deploymentRoot.exists())
                    throw new DeploymentException("Deployment root does not exist." + deploymentRoot);

                // Create the deployment unit context
                final DeploymentUnitContextImpl deploymentUnitContext = new DeploymentUnitContextImpl(deploymentName);
                attachVirtualFile(deploymentUnitContext, deploymentRoot);

                // Setup VFS mount service
                // TODO: We should make sure this is an archive first...
                final ServiceName mountServiceName = MOUNT_SERVICE_NAME.append(deploymentName);
                final VFSMountService vfsMountService = new VFSMountService(deploymentRoot.getPathName(), tempFileProvider, false);
                batchBuilder.addService(mountServiceName, vfsMountService)
                    .setInitialMode(ServiceController.Mode.ON_DEMAND);

                // Setup deployment service
                final ServiceName deploymentServiceName = DeploymentService.SERVICE_NAME.append(deploymentName);
                final DeploymentService deploymentService = new DeploymentService(deploymentName);
                final BatchServiceBuilder deploymentServiceBuilder = batchBuilder.addService(deploymentServiceName, deploymentService)
                    .setInitialMode(ServiceController.Mode.IMMEDIATE);
                deploymentServiceBuilder.addDependency(mountServiceName);
                deploymentServiceBuilder.addDependency(DeploymentChainProvider.SERVICE_NAME)
                    .toInjector(new TranslatingInjector<DeploymentChainProvider, DeploymentChain>(
                            new DeploymentChainProviderTranslator(deploymentRoot),
                            new PropertyInjector(DeploymentService.DEPLOYMENT_CHAIN_PROPERTY, Values.immediateValue(deploymentService)))
                    );
                deploymentServiceBuilder.addDependency(DeploymentModuleLoaderProvider.SERVICE_NAME)
                    .toInjector(new TranslatingInjector<DeploymentModuleLoaderProvider, DeploymentModuleLoader>(
                            new DeploymentModuleLoaderProviderTranslator(deploymentRoot),
                            new PropertyInjector(DeploymentService.MODULE_LOADER_PROPERTY, Values.immediateValue(deploymentService)))
                    );


                final Deployment deployment = new Deployment(deploymentName, deploymentRoot, deploymentServiceName, deploymentService, deploymentUnitContext);
                deployments.add(deployment);
            }

            // Setup the listener for a new batch
            deploymentServiceListener.startBatch(new Runnable() {
                public void run() {
                    try {
                        executeDeploymentProcessors(deployments);
                    } catch(DeploymentException e) {
                        future.setDeploymentResult(new DeploymentResultImpl(DeploymentResult.Result.FAILURE, e, Collections.<ServiceName, StartException>emptyMap(), 0L, 0));
                        return;
                    }
                    try {
                        executeDeploymentItems(future, deployments, deploymentServiceListener);
                    } catch(DeploymentException e) {
                        future.setDeploymentResult(new DeploymentResultImpl(DeploymentResult.Result.FAILURE, e, Collections.<ServiceName, StartException>emptyMap(), 0L, 0));
                    }
                }
            });

            // Install the batch.
            batchBuilder.install();
            deploymentServiceListener.finishBatch();
        } catch(DeploymentException e) {
            future.setDeploymentResult(new DeploymentResultImpl(DeploymentResult.Result.FAILURE, e, Collections.<ServiceName, StartException>emptyMap(), 0L, 0));
        } catch(Throwable t) {
            future.setDeploymentResult(new DeploymentResultImpl(DeploymentResult.Result.FAILURE, new DeploymentException(t), Collections.<ServiceName, StartException>emptyMap(), 0L, 0));
        }
    }

    /**
     * Phase 2 - Execute deployment processors
     */
    private void executeDeploymentProcessors(final List<Deployment> deployments) throws DeploymentException {
        for(Deployment deployment : deployments) {
            final VirtualFile deploymentRoot = deployment.deploymentRoot;

            // Determine which deployment chain to use for this deployment
            final DeploymentChain deploymentChain = deployment.deploymentService.getDeploymentChain();

            // Determine which deployment module loader to use for this deployment
            final DeploymentModuleLoader deploymentModuleLoader = deployment.deploymentService.getModuleLoader();
            DeploymentModuleLoaderSelector.CURRENT_MODULE_LOADER.set(deploymentModuleLoader);

            final DeploymentUnitContext deploymentUnitContext = deployment.deploymentUnitContext;

            // Execute the deployment chain
            logger.debugf("Deployment processor starting with chain: %s", deploymentChain);
            try {
                deploymentChain.processDeployment(deploymentUnitContext);
            } catch(DeploymentUnitProcessingException e) {
                throw new DeploymentException("Failed to process deployment chain.", e);
            } finally {
                DeploymentModuleLoaderSelector.CURRENT_MODULE_LOADER.set(null);
            }
        }
    }

    /**
     * Phase 3 - Create the module and execute the deployment items
     */
    private void executeDeploymentItems(final DeploymentResultImpl.FutureImpl future, final List<Deployment> deployments, final DeploymentServiceListener deploymentServiceListener) throws DeploymentException {
        // Setup batch
        final ServiceContainer serviceContainer = this.serviceContainer;
        final BatchBuilder batchBuilder = serviceContainer.batchBuilder();
        batchBuilder.addListener(deploymentServiceListener);

        // Setup the listener for a new batch
        deploymentServiceListener.startBatch();

        for(Deployment deployment : deployments) {
            final DeploymentUnitContextImpl deploymentUnitContext = deployment.deploymentUnitContext;

            // Setup deployment module
            // Determine which deployment module loader to use for this deployment
            final DeploymentModuleLoader deploymentModuleLoader = deployment.deploymentService.getModuleLoader();
            Module module = null;
            final ModuleConfig moduleConfig = deploymentUnitContext.getAttachment(ModuleConfig.ATTACHMENT_KEY);
            if(moduleConfig != null) {
                try {
                    module = deploymentModuleLoader.loadModule(moduleConfig.getIdentifier());
                } catch(ModuleLoadException e) {
                    throw new DeploymentException("Failed to load deployment module.  The module spec was likely not added to the deployment module loader", e);
                }
            }

            // Create a sub batch for the deployment
            final BatchBuilder subBatchBuilder = batchBuilder.subBatchBuilder();

            // Install dependency on the deployment service
            subBatchBuilder.addDependency(deployment.deploymentServiceName);

            final ClassLoader currentCl = getContextClassLoader();
            try {
                if(module != null) {
                    setContextClassLoader(module.getClassLoader());
                }
                DeploymentModuleLoaderSelector.CURRENT_MODULE_LOADER.set(deploymentModuleLoader);

                // Construct an item context
                final DeploymentItemContext deploymentItemContext = new DeploymentItemContextImpl(module, subBatchBuilder);

                // Process all the deployment items with the item context
                final Collection<DeploymentItem> deploymentItems = deploymentUnitContext.getDeploymentItems();
                for(DeploymentItem deploymentItem : deploymentItems) {
                    deploymentItem.install(deploymentItemContext);
                }
            } finally {
                setContextClassLoader(currentCl);
                DeploymentModuleLoaderSelector.CURRENT_MODULE_LOADER.set(null);
            }
        }

        // Install the batch.
        try {
            batchBuilder.install();
            deploymentServiceListener.finishBatch();
            deploymentServiceListener.finishDeployment();
        } catch(ServiceRegistryException e) {
            throw new DeploymentException(e);
        }
    }

    @Override
    public void undeploy(VirtualFile... deploymentRoots) throws DeploymentException {
        final ServiceContainer serviceContainer = this.serviceContainer;
        final List<ServiceController<?>> serviceControllers = new ArrayList<ServiceController<?>>(deploymentRoots.length);
        for(VirtualFile deploymentRoot : deploymentRoots) {
            final String deploymentName = deploymentRoot.getName();
            final ServiceName deploymentServiceName = DeploymentService.SERVICE_NAME.append(deploymentName);
            final ServiceController<?> serviceController = serviceContainer.getService(deploymentServiceName);
            if(serviceController == null)
                throw new DeploymentException("Failed to undeploy " + deploymentRoot + ". Deployment service does not exist.");
            serviceControllers.add(serviceController);
        }
        ServiceUtils.undeployAll(new Runnable() {
            @Override
            public void run() {
                // Do something..
            }
        }, serviceControllers);
    }

    private ClassLoader getContextClassLoader() {
        return AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {
            public ClassLoader run() {
                return Thread.currentThread().getContextClassLoader();
            }
        });
    }

    private void setContextClassLoader(final ClassLoader classLoader) {
        AccessController.doPrivileged(new PrivilegedAction<Void>() {
            @Override
            public Void run() {
                Thread.currentThread().setContextClassLoader(classLoader);
                return null;
            }
        });
    }

    private static final class Deployment {
        private final String name;
        private final VirtualFile deploymentRoot;
        private final ServiceName deploymentServiceName;
        private final DeploymentService deploymentService;
        private final DeploymentUnitContextImpl deploymentUnitContext;

        private Deployment(String name, VirtualFile deploymentRoot, ServiceName deploymentServiceName, final DeploymentService deploymentService, DeploymentUnitContextImpl deploymentUnitContext) {
            this.name = name;
            this.deploymentRoot = deploymentRoot;
            this.deploymentServiceName = deploymentServiceName;
            this.deploymentService = deploymentService;
            this.deploymentUnitContext = deploymentUnitContext;
        }
    }
}