/*******************************************************************************
 * Copyright (c) 2007, 2018 Wind River Systems, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Contributors:
 * Michael Scharf (Wind River) - initial API and implementation
 *******************************************************************************/
package org.eclipse.terminal.internal.model;

import org.eclipse.terminal.model.ITerminalTextData;

public class TerminalTextDataStoreTest extends AbstractITerminalTextDataTest {
	@Override
	protected ITerminalTextData makeITerminalTextData() {
		return new TerminalTextDataStore();
	}

}
