/*******************************************************************************
 * Copyright (c) 2010, 2015 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Lars Vogel <Lars.Vogel@vogella.com> - Bug 474274
 ******************************************************************************/
package org.eclipse.e4.core.internal.tests.di;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.core.di.extensions.EventTopic;
import org.junit.jupiter.api.Test;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
/**
 * Checks that injected objects that do not have normal links
 * established to the context are still notified on context
 * disposal.
 * (No links: nothing was actually injected; or only IEclipseContext was injected;
 * or constructor injection was used.)
 * See bug https://bugs.eclipse.org/bugs/show_bug.cgi?id=301462 .
 */
public class DisposeClassLinkTest {

	public static class MyTest {
		private int count = 0;

		public int getCount() {
			return count;
		}

		@PreDestroy
		void preDestroy() {
			count++;
		}
	}

	public static class InjectionObject {

		@Inject
		IEclipseContext context;

		int postConstruct = 0;
		int preDestroy = 0;

		@PostConstruct
		void construct() {
			postConstruct++;
		}

		@PreDestroy
		void destroy() {
			preDestroy++;
		}
	}

	public static class TestBug430041 {
		int preDestroy = 0;

		@Inject
		@Optional
		public void inject(@EventTopic("Bla") String bla) {

		}

		@PreDestroy
		void destroy() {
			preDestroy++;
		}
	}

	@Test
	public void testMake() throws Exception {
		IEclipseContext context = EclipseContextFactory.create();
		MyTest test = ContextInjectionFactory.make(MyTest.class, context);

		assertEquals(0, test.getCount());
		context.dispose();
		assertEquals(1, test.getCount());
	}

	@Test
	public void testDisposeParent() throws Exception {
		IEclipseContext parentContext = EclipseContextFactory.create();
		IEclipseContext context = parentContext.createChild();
		MyTest test = ContextInjectionFactory.make(MyTest.class, context);

		assertEquals(0, test.getCount());
		context.dispose();
		assertEquals(1, test.getCount());
		parentContext.dispose();
		assertEquals(1, test.getCount());
	}

	@Test
	public void testInject() throws Exception {
		IEclipseContext parentContext = EclipseContextFactory.create();
		IEclipseContext context = parentContext.createChild();
		MyTest test = new MyTest();
		ContextInjectionFactory.inject(test, context);

		assertEquals(0, test.getCount());
		context.dispose();
		assertEquals(1, test.getCount());
	}

	@Test
	public void testDisposeParentFirst() throws Exception {
		IEclipseContext parentContext = EclipseContextFactory.create();
		IEclipseContext context = parentContext.createChild();
		MyTest test = new MyTest();
		ContextInjectionFactory.inject(test, context);

		assertEquals(0, test.getCount());
		context.dispose();
		assertEquals(1, test.getCount());
		parentContext.dispose();
		assertEquals(1, test.getCount());
	}

	@Test
	public void testInjectedWithContext() throws Exception {
		IEclipseContext context = EclipseContextFactory.create();

		InjectionObject obj = ContextInjectionFactory.make(InjectionObject.class, context);

		assertEquals(context, obj.context, "The object has been injected with the context");
		assertEquals(1, obj.postConstruct, "@PostConstruct should have been called once");
		assertEquals(0, obj.preDestroy, "@PreDestroy should not have been called");

		context.dispose();

		assertNotNull(obj.context);
		assertEquals(1, obj.postConstruct, "@PostConstruct should only have been called once");
		assertEquals(1, obj.preDestroy, "@PreDestroy should have been called during uninjection");
	}

	@Test
	public void testBug430041() {
		IEclipseContext context = EclipseContextFactory.create();
		TestBug430041 obj = ContextInjectionFactory.make(TestBug430041.class, context);
		context.dispose();
		assertEquals(1, obj.preDestroy);
	}
}
