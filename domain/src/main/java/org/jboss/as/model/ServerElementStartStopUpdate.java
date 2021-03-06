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
 * Update to the start property of a {@link ServerElement}.
 *
 * @author Brian Stansberry
 */
public class ServerElementStartStopUpdate extends AbstractModelUpdate<ServerElement, Void> {

    private static final long serialVersionUID = -5766717739615737224L;

    private final boolean start;

    public ServerElementStartStopUpdate(final boolean start) {
        this.start = start;
    }

    @Override
    public ServerElementStartStopUpdate getCompensatingUpdate(ServerElement original) {
        return new ServerElementStartStopUpdate(original.isStart());
    }

    @Override
    protected AbstractServerModelUpdate<Void> getServerModelUpdate() {
        return null;
    }

    @Override
    protected void applyUpdate(ServerElement element) throws UpdateFailedException {
        element.setStart(start);
    }

    @Override
    public Class<ServerElement> getModelElementType() {
        return ServerElement.class;
    }

}
