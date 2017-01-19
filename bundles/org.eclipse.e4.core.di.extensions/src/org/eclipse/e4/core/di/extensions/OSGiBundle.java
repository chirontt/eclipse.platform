/*******************************************************************************
 * Copyright (c) 2013 Markus Alexander Kuppe and others. All rights reserved.
 * This program and the accompanying materials are made available under the terms
 * of the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Markus Alexander Kuppe - initial API and implementation
 ******************************************************************************/
package org.eclipse.e4.core.di.extensions;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import javax.inject.Qualifier;

/**
 * A method or field of type {@link org.osgi.framework.BundleContext} and
 * annotated with {@link OSGiBundle} will be injected with the from the bundle
 * containing the class if the annotated type is a
 * {@link org.osgi.framework.BundleContext} and the bundle's state is
 * {@link org.osgi.framework.Bundle#ACTIVE}.
 * <p>
 * If the method or field type is of {@link Bundle}, the bundle containing the
 * class will be injected even for bundles in the
 * {@link org.osgi.framework.Bundle#RESOLVED} state.
 * </p>
 * <p>
 * The {@link org.osgi.framework.Bundle} injected will be the
 * {@link org.osgi.framework.Bundle} that supplied the class of the object
 * instance. For example, in:
 * 
 * <pre>
 * class A {
 * 	&#64;Inject
 * 	&#64;OSGiBundle
 * 	BundleContext context;
 * }
 * 
 * class B extends A {
 * }
 * </pre>
 * 
 * <code>ContextInjectionFactory.make(B.class)</code> will inject the
 * {@link org.osgi.framework.BundleContext} for the bundle that supplies class
 * B, not class A.
 * 
 * </p>
 */
@Qualifier
@Documented
@Target({ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface OSGiBundle {
	// Nop
}
