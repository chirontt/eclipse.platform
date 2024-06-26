/*******************************************************************************
 * Copyright (c) 2022 Andrey Loskutov <loskutov@gmx.de> and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Andrey Loskutov <loskutov@gmx.de> - initial API and implementation
 *******************************************************************************/
package org.eclipse.core.tests.resources.session;

import static org.eclipse.core.tests.resources.ResourceTestPluginConstants.PI_RESOURCES_TESTS;
import static org.eclipse.core.tests.resources.ResourceTestUtil.createTestMonitor;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.tests.harness.session.SessionTestExtension;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Tests that encoding is set to UTF-8 in an empty workspace and only if no
 * preference set already
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TestWorkspaceEncodingNewWorkspace {

	private static final String CHARSET = "UTF-16";

	@RegisterExtension
	static SessionTestExtension sessionTestExtension = SessionTestExtension.forPlugin(PI_RESOURCES_TESTS)
			.withCustomization(SessionTestExtension.createCustomWorkspace()).create();

	@Test
	@Order(1)
	public void testExpectedEncoding1() throws Exception {
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		String charset = workspace.getRoot().getDefaultCharset(false);
		// Should be default
		assertEquals("UTF-8", charset);
		// Set something else
		workspace.getRoot().setDefaultCharset(CHARSET, createTestMonitor());
		workspace.save(true, createTestMonitor());
	}

	@Test
	@Order(2)
	public void testExpectedEncoding2() throws Exception {
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		String charset = workspace.getRoot().getDefaultCharset(false);
		// Shouldn't be changed anymore
		assertEquals(CHARSET, charset);
	}

}
