/*******************************************************************************
 * Copyright (c) 2015, 2018 Wind River Systems, Inc. and others. All rights reserved.
 * This program and the accompanying materials are made available under the terms
 * of the Eclipse Public License 2.0 which accompanies this distribution, and is
 * available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.terminal.view.core.internal;

import org.eclipse.terminal.view.core.ITerminalService;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.util.tracker.ServiceTracker;

/**
 * The activator class controls the plug-in life cycle
 */
public class CoreBundleActivator implements BundleActivator {
	// The bundle context
	private static BundleContext context;

	/**
	 * Returns the bundle context
	 *
	 * @return the bundle context
	 */
	public static BundleContext getContext() {
		return context;
	}

	/**
	 * Convenience method which returns the unique identifier of this plug-in.
	 */
	public static String getUniqueIdentifier() {
		if (getContext() != null && getContext().getBundle() != null) {
			return getContext().getBundle().getSymbolicName();
		}
		return "org.eclipse.terminal.view.core"; //$NON-NLS-1$
	}

	@Override
	public void start(BundleContext bundleContext) throws Exception {
		CoreBundleActivator.context = bundleContext;
	}

	@Override
	public void stop(BundleContext bundleContext) throws Exception {
		CoreBundleActivator.context = null;
	}

	private static ServiceTracker<ITerminalService, ITerminalService> serviceTracker;

	public static synchronized ITerminalService getTerminalService() {
		if (serviceTracker == null) {
			serviceTracker = new ServiceTracker<>(getContext(), ITerminalService.class, null);
			serviceTracker.open();
		}
		return serviceTracker.getService();
	}
}
