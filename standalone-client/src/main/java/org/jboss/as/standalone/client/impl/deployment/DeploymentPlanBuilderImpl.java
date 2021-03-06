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

package org.jboss.as.standalone.client.impl.deployment;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jboss.as.standalone.client.api.deployment.AddDeploymentPlanBuilder;
import org.jboss.as.standalone.client.api.deployment.DeploymentAction;
import org.jboss.as.standalone.client.api.deployment.DeploymentAction.Type;
import org.jboss.as.standalone.client.api.deployment.DeploymentPlan;
import org.jboss.as.standalone.client.api.deployment.DeploymentPlanBuilder;
import org.jboss.as.standalone.client.api.deployment.DuplicateDeploymentNameException;
import org.jboss.as.standalone.client.api.deployment.InitialDeploymentPlanBuilder;
import org.jboss.as.standalone.client.api.deployment.ReplaceDeploymentPlanBuilder;
import org.jboss.as.standalone.client.api.deployment.UndeployDeploymentPlanBuilder;
/**
 * {@link DeploymentPlanBuilder} implementation meant to handle in-VM calls.
 *
 * @author Brian Stansberry
 */
class DeploymentPlanBuilderImpl
    implements AddDeploymentPlanBuilder, InitialDeploymentPlanBuilder, UndeployDeploymentPlanBuilder  {

    private final DeploymentContentDistributor deploymentDistributor;
    private final boolean shutdown;
    private final long gracefulShutdownPeriod;
    private final boolean globalRollback;

    private final List<DeploymentActionImpl> deploymentActions = new ArrayList<DeploymentActionImpl>();

    DeploymentPlanBuilderImpl(DeploymentContentDistributor deploymentDistributor) {
        if (deploymentDistributor == null)
            throw new IllegalArgumentException("deploymentDistributor is null");
        this.deploymentDistributor = deploymentDistributor;
        this.shutdown = false;
        this.globalRollback = false;
        this.gracefulShutdownPeriod = -1;
    }

    DeploymentPlanBuilderImpl(DeploymentPlanBuilderImpl existing) {
        this.deploymentActions.addAll(existing.deploymentActions);
        this.deploymentDistributor = existing.deploymentDistributor;
        this.shutdown = existing.shutdown;
        this.globalRollback = existing.globalRollback;
        this.gracefulShutdownPeriod = existing.gracefulShutdownPeriod;
    }

    DeploymentPlanBuilderImpl(DeploymentPlanBuilderImpl existing, boolean globalRollback) {
        this.deploymentActions.addAll(existing.deploymentActions);
        this.deploymentDistributor = existing.deploymentDistributor;
        this.shutdown = false;
        this.globalRollback = globalRollback;
        this.gracefulShutdownPeriod = -1;
    }

    DeploymentPlanBuilderImpl(DeploymentPlanBuilderImpl existing, long gracefulShutdownPeriod) {
        this.deploymentActions.addAll(existing.deploymentActions);
        this.deploymentDistributor = existing.deploymentDistributor;
        this.shutdown = true;
        this.globalRollback = false;
        this.gracefulShutdownPeriod = gracefulShutdownPeriod;
    }

    DeploymentPlanBuilderImpl(DeploymentPlanBuilderImpl existing, DeploymentActionImpl modification) {
        this(existing);
        this.deploymentActions.add(modification);
    }

    @Override
    public DeploymentAction getLastAction() {
        return deploymentActions.size() == 0 ? null : deploymentActions.get(deploymentActions.size() - 1);
    }

    @Override
    public List<DeploymentAction> getDeploymentActions() {
        return new ArrayList<DeploymentAction>(deploymentActions);
    }

    @Override
    public long getGracefulShutdownTimeout() {
        return gracefulShutdownPeriod;
    }

    @Override
    public boolean isGlobalRollback() {
        return globalRollback;
    }

    @Override
    public boolean isGracefulShutdown() {
        return shutdown && gracefulShutdownPeriod > -1;
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public DeploymentPlan build() {
        return new DeploymentPlanImpl(Collections.unmodifiableList(deploymentActions), globalRollback, shutdown, gracefulShutdownPeriod);
    }

    @Override
    public AddDeploymentPlanBuilder add(File file) throws IOException, DuplicateDeploymentNameException {
        String name = file.getName();
        return add(name, name, file.toURI().toURL());
    }

    @Override
    public AddDeploymentPlanBuilder add(URL url) throws IOException, DuplicateDeploymentNameException {
        String name = getName(url);
        return add(name, name, url);
    }

    @Override
    public AddDeploymentPlanBuilder add(String name, File file) throws IOException, DuplicateDeploymentNameException {
        return add(name, file.getName(), file.toURI().toURL());
    }

    @Override
    public AddDeploymentPlanBuilder add(String name, URL url) throws IOException, DuplicateDeploymentNameException {
        String commonName = getName(url);
        return add(name, commonName, url);
    }

    private AddDeploymentPlanBuilder add(String name, String commonName, URL url) throws IOException, DuplicateDeploymentNameException {
        URLConnection conn = url.openConnection();
        conn.connect();
        InputStream stream = conn.getInputStream();
        try {
            return add(name, commonName, stream);
        }
        finally {
            try { stream.close(); } catch (Exception ignored) {}
        }
    }

    private DeploymentPlanBuilder replace(String name, String commonName, URL url) throws IOException {
        URLConnection conn = url.openConnection();
        conn.connect();
        InputStream stream = conn.getInputStream();
        try {
            return replace(name, commonName, stream);
        }
        finally {
            try { stream.close(); } catch (Exception ignored) {}
        }
    }

    @Override
    public AddDeploymentPlanBuilder add(String name, InputStream stream) throws IOException, DuplicateDeploymentNameException {
        return add(name, name, stream);
    }

    @Override
    public AddDeploymentPlanBuilder add(String name, String commonName, InputStream stream) throws IOException, DuplicateDeploymentNameException {
        byte[] hash = deploymentDistributor.distributeDeploymentContent(name, commonName, stream);
        DeploymentActionImpl mod = DeploymentActionImpl.getAddAction(name, commonName, hash);
        return new DeploymentPlanBuilderImpl(this, mod);
    }

    /* (non-Javadoc)
     * @see org.jboss.as.deployment.client.api.server.AddDeploymentPlanBuilder#andDeploy()
     */
    @Override
    public DeploymentPlanBuilder andDeploy() {
        String addedKey = getAddedContentKey();
        DeploymentActionImpl deployMod = DeploymentActionImpl.getDeployAction(addedKey);
        return new DeploymentPlanBuilderImpl(this, deployMod);
    }

    /* (non-Javadoc)
     * @see org.jboss.as.deployment.client.api.server.AddDeploymentPlanBuilder#andReplace(java.lang.String)
     */
    @Override
    public ReplaceDeploymentPlanBuilder andReplace(String toReplace) {
        String newContentKey = getAddedContentKey();
        return replace(newContentKey, toReplace);
    }

    @Override
    public DeploymentPlanBuilder deploy(String key) {
        DeploymentActionImpl mod = DeploymentActionImpl.getDeployAction(key);
        return new DeploymentPlanBuilderImpl(this, mod);
    }

    @Override
    public UndeployDeploymentPlanBuilder undeploy(String key) {
        DeploymentActionImpl mod = DeploymentActionImpl.getUndeployAction(key);
        return new DeploymentPlanBuilderImpl(this, mod);
    }

    @Override
    public DeploymentPlanBuilder redeploy(String deploymentName) {
        DeploymentActionImpl mod = DeploymentActionImpl.getRedeployAction(deploymentName);
        return new DeploymentPlanBuilderImpl(this, mod);
    }

    @Override
    public ReplaceDeploymentPlanBuilder replace(String replacement, String toReplace) {
        DeploymentActionImpl mod = DeploymentActionImpl.getReplaceAction(replacement, toReplace);
        return new ReplaceDeploymentPlanBuilderImpl(this, mod);
    }

    @Override
    public DeploymentPlanBuilder replace(File file) throws IOException {
        String name = file.getName();
        return replace(name, name, file.toURI().toURL());
    }

    @Override
    public DeploymentPlanBuilder replace(URL url) throws IOException {
        String name = getName(url);
        return replace(name, name, url);
    }

    @Override
    public DeploymentPlanBuilder replace(String name, File file) throws IOException {
        return replace(name, name, file.toURI().toURL());
    }

    @Override
    public DeploymentPlanBuilder replace(String name, URL url) throws IOException {
        return replace(name, name, url);
    }

    @Override
    public DeploymentPlanBuilder replace(String name, InputStream stream) throws IOException {
        return replace(name, name, stream);
    }

    @Override
    public DeploymentPlanBuilder replace(String name, String commonName, InputStream stream) throws IOException {
        byte[] hash = deploymentDistributor.distributeReplacementDeploymentContent(name, commonName, stream);
        DeploymentActionImpl mod = DeploymentActionImpl.getFullReplaceAction(name, commonName, hash);
        return new DeploymentPlanBuilderImpl(this, mod);
    }

    @Override
    public DeploymentPlanBuilder andRemoveUndeployed() {
        DeploymentAction last = getLastAction();
        if (last.getType() != Type.UNDEPLOY) {
            // Someone cast to the impl class instead of using the interface
            throw new IllegalStateException("Preceding action was not a " + Type.UNDEPLOY);
        }
        DeploymentActionImpl removeMod = DeploymentActionImpl.getRemoveAction(last.getDeploymentUnitUniqueName());
        return new DeploymentPlanBuilderImpl(this, removeMod);
    }

    @Override
    public DeploymentPlanBuilder remove(String key) {
        DeploymentActionImpl removeMod = DeploymentActionImpl.getRemoveAction(key);
        return new DeploymentPlanBuilderImpl(this, removeMod);
    }

    @Override
    public DeploymentPlanBuilder withRollback() {
        if (deploymentActions.size() > 0) {
            // Someone has cast to this impl class
            throw new IllegalStateException(InitialDeploymentPlanBuilder.class.getSimpleName() + " operations are not allowed after content and deployment modifications");
        }
        if (shutdown)
            throw new IllegalStateException("Global rollback is not compatible with a server restart");
        return new DeploymentPlanBuilderImpl(this, true);
    }

    @Override
    public DeploymentPlanBuilder withGracefulShutdown(long timeout, TimeUnit timeUnit) {
        // TODO determine how to remove content. Perhaps with a signal to the
        // deployment repository service such that as part of shutdown after
        // undeploys are done it then removes the content?

        if (deploymentActions.size() > 0) {
            // Someone has to cast this impl class
            throw new IllegalStateException(InitialDeploymentPlanBuilder.class.getSimpleName() + " operations are not allowed after content and deployment modifications");
        }
        if (globalRollback)
            throw new IllegalStateException("Global rollback is not compatible with a server restart");

        long period = timeUnit.toMillis(timeout);
        if (shutdown && period != gracefulShutdownPeriod) {
            throw new IllegalStateException("Graceful shutdown already configured with a timeout of " + gracefulShutdownPeriod + " ms");
        }
        return new DeploymentPlanBuilderImpl(this, period);
    }

    @Override
    public DeploymentPlanBuilder withShutdown() {
        // TODO determine how to remove content. Perhaps with a signal to the
        // deployment repository service such that as part of shutdown after
        // undeploys are done it then removes the content?

        if (deploymentActions.size() > 0) {
            // Someone has to cast this impl class
            throw new IllegalStateException(InitialDeploymentPlanBuilder.class.getSimpleName() + " operations are not allowed after content and deployment modifications");
        }
        if (globalRollback)
            throw new IllegalStateException("Global rollback is not compatible with a server restart");

        if (shutdown && gracefulShutdownPeriod != -1) {
            throw new IllegalStateException("Graceful shutdown already configured with a timeout of " + gracefulShutdownPeriod + " ms");
        }
        return new DeploymentPlanBuilderImpl(this, -1);
    }


    private String getAddedContentKey() {
        DeploymentAction last = getLastAction();
        if (last.getType() != Type.ADD) {
            // Someone cast to the impl class instead of using the interface
            throw new IllegalStateException("Preceding action was not a " + Type.ADD);
        }
        return last.getDeploymentUnitUniqueName();
    }

    private static String getName(URL url) {
        if ("file".equals(url.getProtocol())) {
            try {
                File f = new File(url.toURI());
                return f.getName();
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException(url + " is not a valid URI", e);
            }
        }

        String path = url.getPath();
        int idx = path.lastIndexOf('/');
        while (idx == path.length() - 1) {
            path = path.substring(0, idx);
            idx = path.lastIndexOf('/');
        }
        if (idx == -1) {
            throw new IllegalArgumentException("Cannot derive a deployment name from " +
                    url + " -- use an overloaded method variant that takes a 'name' parameter");
        }

        return path.substring(idx + 1);
    }
}
