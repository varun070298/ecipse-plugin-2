/*******************************************************************************
 * Copyright (c) 2011 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.internal.ccvs.core;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.team.core.RepositoryProviderType;
import org.eclipse.team.core.importing.provisional.BundleImporterDelegate;

/**
 * Handles SCM CVS headers of the following form. Tag and project name can be specified
 * as extra attributes. When a tag is unspecified, the HEAD tag is used. When a project
 * name is unspecified, it is generated by the module name.
 * <pre>
 * scm:cvs&lt;delimiter&gt;&lt;method&gt;&lt;delimiter&gt;path_to_repository&lt;delimiter&gt;module_name[;tag=version][;project=name]
 * scm:psf&lt;delimiter&gt;&lt;method&gt;&lt;delimiter&gt;path_to_repository&lt;delimiter&gt;module_name[;tag=version][;project=name]
 * </pre>
 */
public class CvsBundleImporterDelegate extends BundleImporterDelegate {

	private static Set SUPPORTED_VALUES;

	private static final String SCM = "scm:"; //$NON-NLS-1$
	private static final String CVS = "cvs"; //$NON-NLS-1$
//	private static final String PSF = "psf"; //$NON-NLS-1$
	private static final String COLON = ":"; //$NON-NLS-1$
	private static final String PIPE = "|"; //$NON-NLS-1$

	private RepositoryProviderType providerType;

	static {
		SUPPORTED_VALUES = new HashSet();
		SUPPORTED_VALUES.add(SCM + CVS + COLON);
		SUPPORTED_VALUES.add(SCM + CVS + PIPE);
//		SUPPORTED_VALUES.add(SCM + PSF + COLON);
//		SUPPORTED_VALUES.add(SCM + PSF + PIPE);
	}
	
	protected Set getSupportedValues() {
		return SUPPORTED_VALUES;
	}
	
	protected RepositoryProviderType getProviderType() {
		if (providerType == null)
			providerType = RepositoryProviderType.getProviderType(CVSProviderPlugin.getTypeId());
		return providerType;
	}

}
