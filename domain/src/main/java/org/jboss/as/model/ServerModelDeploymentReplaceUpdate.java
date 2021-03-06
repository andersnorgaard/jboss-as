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

package org.jboss.as.model;

/**
* Update used when updating a deployment element to be started.
*
* @author Brian Stansberry
*/
public class ServerModelDeploymentReplaceUpdate extends AbstractServerModelUpdate<Void> {
    private static final long serialVersionUID = 5773083013951607950L;

    private final String newDeployment;
    private final String newDeploymentRuntimeName;
    private final byte[] newDeploymentHash;
    private final String toReplace;
    private final ServerDeploymentStartStopHandler startStopHandler;
    private ServerGroupDeploymentElement deploymentElement;

    public ServerModelDeploymentReplaceUpdate(final String newDeployment, final String newDeploymentRuntimeName, final byte[] newDeploymentHash, final String toReplace) {
        super(false, true);
        if (newDeployment == null)
            throw new IllegalArgumentException("newDeployment is null");
        if (newDeploymentRuntimeName == null)
            throw new IllegalArgumentException("newDeploymentRuntimeName is null");
        if (newDeploymentHash == null)
            throw new IllegalArgumentException("newDeploymentHash is null");
        if (toReplace == null)
            throw new IllegalArgumentException("toReplace is null");
        this.newDeployment = newDeployment;
        this.toReplace = toReplace;
        startStopHandler = new ServerDeploymentStartStopHandler();
        this.newDeploymentRuntimeName = newDeploymentRuntimeName;
        this.newDeploymentHash = newDeploymentHash;
    }

    public ServerModelDeploymentReplaceUpdate(final String newDeployment, final String toReplace) {
        super(false, true);
        if (newDeployment == null)
            throw new IllegalArgumentException("newDeployment is null");
        if (toReplace == null)
            throw new IllegalArgumentException("toReplace is null");
        this.newDeployment = newDeployment;
        this.toReplace = toReplace;
        startStopHandler = new ServerDeploymentStartStopHandler();
        this.newDeploymentRuntimeName = null;
        this.newDeploymentHash = null;
    }

    @Override
    public void applyUpdate(ServerModel standalone) throws UpdateFailedException {

        ServerGroupDeploymentElement undeploymentElement = standalone.getDeployment(toReplace);

        if (undeploymentElement == null) {
            throw new UpdateFailedException("Unknown deployment " + newDeployment);
        }

        if (newDeploymentRuntimeName != null) {
            standalone.addDeployment(new ServerGroupDeploymentElement(newDeployment, newDeploymentRuntimeName, newDeploymentHash, false));
        }
        deploymentElement = standalone.getDeployment(newDeployment);

        if (deploymentElement == null) {
            throw new UpdateFailedException("Unknown deployment " + newDeployment);
        }

        undeploymentElement.setStart(false);
        deploymentElement.setStart(true);
    }

    @Override
    public <P> void applyUpdate(UpdateContext updateContext,
            UpdateResultHandler<? super Void, P> resultHandler, P param) {
        if (deploymentElement != null) {
            startStopHandler.redeploy(newDeployment, deploymentElement.getRuntimeName(), deploymentElement.getSha1Hash(),
                    updateContext.getServiceContainer(), resultHandler, param);
        }
        else if (resultHandler != null) {
            // We shouldn't be able to get here, as the model update should have failed,
            // but just in case
            resultHandler.handleFailure(new IllegalStateException("Unknown deployment " + newDeployment), param);
        }
    }

    @Override
    public ServerModelDeploymentReplaceUpdate getCompensatingUpdate(ServerModel original) {

        ServerGroupDeploymentElement deploymentElement = original.getDeployment(newDeployment);
        ServerGroupDeploymentElement undeploymentElement = original.getDeployment(toReplace);
        if (deploymentElement == null || undeploymentElement == null) {
            // We will fail in applyUpdate and won't do anything, so don't
            // provide a compensating update that actually would do something
            return null;
        }
        return new ServerModelDeploymentReplaceUpdate(toReplace, newDeployment);
    }
}
