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
package org.eclipse.terminal.view.ui.internal.handler;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.terminal.view.ui.IUIConstants;
import org.eclipse.terminal.view.ui.internal.UIPlugin;
import org.eclipse.terminal.view.ui.launcher.ITerminalConsoleViewManager;

/**
 * New Terminal View handler implementation
 */
public class NewTerminalViewHandler extends AbstractTriggerCommandHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		ITerminalConsoleViewManager consoleManager = UIPlugin.getConsoleManager();
		String secondaryId = consoleManager.getNextTerminalSecondaryId(IUIConstants.ID);
		consoleManager.showConsoleView(IUIConstants.ID, secondaryId);

		triggerCommand("org.eclipse.terminal.view.ui.command.launchToolbar", null); //$NON-NLS-1$

		return null;
	}

}
