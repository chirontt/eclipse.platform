/*******************************************************************************
 * Copyright (c) 2000, 2013 IBM Corporation and others.
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
 *******************************************************************************/
package org.eclipse.compare.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.ICompareFilter;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;

/**
 * Toggles the activation of a compare filter
 */
public class ChangeCompareFilterPropertyAction extends Action implements
		IPropertyChangeListener, DisposeListener {

	public static final String COMPARE_FILTERS = "COMPARE_FILTERS"; //$NON-NLS-1$
	public static final String COMPARE_FILTER_ACTIONS = "COMPARE_FILTER_ACTIONS"; //$NON-NLS-1$
	public static final String COMPARE_FILTERS_INITIALIZING = "COMPARE_FILTERS_INITIALIZING"; //$NON-NLS-1$

	private CompareConfiguration fCompareConfiguration;
	private final ResourceBundle fBundle;
	private final String fPrefix = "filter."; //$NON-NLS-1$
	private final CompareFilterDescriptor fCompareFilterDescriptor;
	private final ICompareFilter fCompareFilter;

	public ChangeCompareFilterPropertyAction(
			CompareFilterDescriptor compareFilterDescriptor,
			CompareConfiguration compareConfiguration) {
		this.fBundle = compareFilterDescriptor.getResourceBundle();
		this.fCompareFilterDescriptor = compareFilterDescriptor;
		this.fCompareFilter = compareFilterDescriptor.createCompareFilter();
		Utilities.initAction(this, fBundle, fPrefix);

		// Utilities only loads images from compare plugin
		setImageDescriptor(compareFilterDescriptor.getImageDescriptor());
		setId(compareFilterDescriptor.getFilterId());
		setActionDefinitionId(compareFilterDescriptor.getDefinitionId());
		setCompareConfiguration(compareConfiguration);

		if (fCompareFilter.isEnabledInitially()) {
			setChecked(true);
			setProperty(true);
		}
	}

	@SuppressWarnings("unchecked")
	void setProperty(boolean state) {
		if (fCompareConfiguration != null) {
			Map<String, ICompareFilter> filters = new HashMap<>();
			if (fCompareConfiguration.getProperty(COMPARE_FILTERS) != null) {
				filters.putAll(
						(Map<String, ICompareFilter>) fCompareConfiguration
						.getProperty(COMPARE_FILTERS));
			}
			if (state) {
				filters.put(fCompareFilterDescriptor.getFilterId(),
						fCompareFilter);
			} else {
				filters.remove(fCompareFilterDescriptor.getFilterId());
			}
			fCompareConfiguration.setProperty(COMPARE_FILTERS, filters);
		}
	}

	@SuppressWarnings("unchecked")
	boolean getProperty() {
		Map<String, ICompareFilter> filters = (Map<String, ICompareFilter>) fCompareConfiguration
				.getProperty(COMPARE_FILTERS);
		if (filters == null) {
			filters = new HashMap<>();
		}
		return filters.containsKey(fCompareFilterDescriptor.getFilterId());
	}

	@Override
	public void run() {
		boolean b = !getProperty();
		setChecked(b);
		setProperty(b);
	}

	@Override
	public void setChecked(boolean state) {
		super.setChecked(state);
		Utilities.initToggleAction(this, fBundle, fPrefix, state);
	}

	public void setCompareConfiguration(CompareConfiguration cc) {
		if (fCompareConfiguration != null) {
			fCompareConfiguration.removePropertyChangeListener(this);
		}
		fCompareConfiguration = cc;
		if (fCompareConfiguration != null) {
			fCompareConfiguration.addPropertyChangeListener(this);
		}
		setChecked(getProperty());
	}

	@SuppressWarnings("unchecked")
	@Override
	public void propertyChange(PropertyChangeEvent event) {
		if (event.getProperty().equals(COMPARE_FILTERS)
				&& event.getNewValue() instanceof Map) {
			setChecked(((Map<String, ICompareFilter>) event.getNewValue())
					.containsKey(fCompareFilterDescriptor.getFilterId()));
		}
	}

	public void dispose() {
		if (fCompareConfiguration != null) {
			fCompareConfiguration.removePropertyChangeListener(this);
		}
	}

	@Override
	public void widgetDisposed(DisposeEvent e) {
		dispose();
	}

	public String getFilterId() {
		return fCompareFilterDescriptor.getFilterId();
	}

	public void setInput(Object input, Object ancestor, Object left,
			Object right) {
		fCompareFilter.setInput(input, ancestor, left, right);
	}
}
