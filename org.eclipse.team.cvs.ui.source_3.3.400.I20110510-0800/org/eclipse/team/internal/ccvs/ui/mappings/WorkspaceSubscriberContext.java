/*******************************************************************************
 * Copyright (c) 2000, 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.internal.ccvs.ui.mappings;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.*;
import org.eclipse.core.resources.mapping.*;
import org.eclipse.core.runtime.*;
import org.eclipse.osgi.util.NLS;
import org.eclipse.team.core.RepositoryProvider;
import org.eclipse.team.core.diff.IDiff;
import org.eclipse.team.core.diff.IThreeWayDiff;
import org.eclipse.team.core.diff.provider.DiffTree;
import org.eclipse.team.core.history.IFileRevision;
import org.eclipse.team.core.mapping.*;
import org.eclipse.team.core.mapping.provider.ResourceDiffTree;
import org.eclipse.team.core.subscribers.Subscriber;
import org.eclipse.team.core.subscribers.SubscriberScopeManager;
import org.eclipse.team.core.synchronize.SyncInfo;
import org.eclipse.team.core.synchronize.SyncInfoFilter;
import org.eclipse.team.core.synchronize.SyncInfoFilter.ContentComparisonSyncInfoFilter;
import org.eclipse.team.core.variants.IResourceVariant;
import org.eclipse.team.internal.ccvs.core.*;
import org.eclipse.team.internal.ccvs.core.client.PruneFolderVisitor;
import org.eclipse.team.internal.ccvs.core.mapping.CVSActiveChangeSetCollector;
import org.eclipse.team.internal.ccvs.core.resources.CVSWorkspaceRoot;
import org.eclipse.team.internal.ccvs.core.resources.RemoteFile;
import org.eclipse.team.internal.ccvs.ui.*;
import org.eclipse.team.internal.ccvs.ui.Policy;
import org.eclipse.team.internal.ccvs.ui.operations.*;
import org.eclipse.team.internal.core.mapping.GroupProgressMonitor;
import org.eclipse.team.internal.core.subscribers.SubscriberDiffTreeEventHandler;

public class WorkspaceSubscriberContext extends CVSSubscriberMergeContext {

	public static final class ChangeSetSubscriberScopeManager extends SubscriberScopeManager {
		private final boolean consultSets;

		private ChangeSetSubscriberScopeManager(String name, ResourceMapping[] mappings, Subscriber subscriber, boolean consultModels, boolean consultSets) {
			super(name, mappings, subscriber, consultModels);
			this.consultSets = consultSets;
		}

		protected ResourceTraversal[] adjustInputTraversals(ResourceTraversal[] traversals) {
			if (isConsultSets())
				return ((CVSActiveChangeSetCollector)CVSUIPlugin.getPlugin().getChangeSetManager()).adjustInputTraversals(traversals);
			return super.adjustInputTraversals(traversals);
		}

		public boolean isConsultSets() {
			return consultSets;
		}
	}

	private final int type;

	public static SubscriberScopeManager createWorkspaceScopeManager(ResourceMapping[] mappings, boolean consultModels, final boolean consultChangeSets) {
		return new ChangeSetSubscriberScopeManager(CVSProviderPlugin.getPlugin().getCVSWorkspaceSubscriber().getName(), mappings, CVSProviderPlugin.getPlugin().getCVSWorkspaceSubscriber(), consultModels, consultChangeSets);
	}
	
	public static SubscriberScopeManager createUpdateScopeManager(ResourceMapping[] mappings, boolean consultModels) {
		return new SubscriberScopeManager(CVSProviderPlugin.getPlugin().getCVSWorkspaceSubscriber().getName(), mappings, CVSProviderPlugin.getPlugin().getCVSWorkspaceSubscriber(), consultModels);
	}
	
	public static WorkspaceSubscriberContext createContext(ISynchronizationScopeManager manager, int type) {
		CVSWorkspaceSubscriber subscriber = CVSProviderPlugin.getPlugin().getCVSWorkspaceSubscriber();
		WorkspaceSubscriberContext mergeContext = new WorkspaceSubscriberContext(subscriber, manager, type);
		mergeContext.initialize();
		return mergeContext;
	}
	
	protected WorkspaceSubscriberContext(CVSWorkspaceSubscriber subscriber, ISynchronizationScopeManager manager, int type) {
		super(subscriber, manager);
		this.type = type;
	}

	public void markAsMerged(IDiff[] nodes, boolean inSyncHint, IProgressMonitor monitor) throws CoreException {
		if (getType() == TWO_WAY) {
			// For, TWO_WAY merges (i.e. replace) should not adjust sync info
			// but should remove the node from the tree so that other models do 
			// not modify the file
			DiffTree tree = ((DiffTree)getDiffTree());
			try {
				tree.beginInput();
				for (int i = 0; i < nodes.length; i++) {
					IDiff diff = nodes[i];
					tree.remove(diff.getPath());
				}
			} finally {
				tree.endInput(monitor);
			}
		} else {
			super.markAsMerged(nodes, inSyncHint, monitor);
		}
	}

	public void markAsMerged(final IDiff diff, final boolean inSyncHint, IProgressMonitor monitor) throws CoreException {
		run(new IWorkspaceRunnable() {
			public void run(IProgressMonitor monitor) throws CoreException {
				// Get the latest sync info for the file (i.e. not what is in the set).
				// We do this because the client may have modified the file since the
				// set was populated.
				IResource resource = getDiffTree().getResource(diff);
				if (resource.getType() != IResource.FILE) {
					if (diff instanceof IThreeWayDiff) {
						IThreeWayDiff twd = (IThreeWayDiff) diff;
						if (resource.getType() == IResource.FOLDER
								&& twd.getKind() == IDiff.ADD 
								&& twd.getDirection() == IThreeWayDiff.INCOMING
								&& resource.exists()) {
							// The folder was created by merge
							SyncInfo info = getSyncInfo(resource);
							if (info instanceof CVSSyncInfo) {
								CVSSyncInfo cvsInfo = (CVSSyncInfo) info;
								cvsInfo.makeInSync();
							}
						}
					}
					return;
				}
				if (getType() == TWO_WAY) {
					// For, TWO_WAY merges (i.e. replace) should not adjust sync info
					// but should remove the node from the tree so that other models do 
					// not modify the file
					((DiffTree)getDiffTree()).remove(diff.getPath());
				} else {
					SyncInfo info = getSyncInfo(resource);
					ensureRemotesMatch(resource, diff, info);
					if (info instanceof CVSSyncInfo) {
						CVSSyncInfo cvsInfo = (CVSSyncInfo) info;
						monitor.beginTask(null, 50 + (inSyncHint ? 100 : 0));
						cvsInfo.makeOutgoing(Policy.subMonitorFor(monitor, 50));
						if (inSyncHint) {
							// Compare the contents of the file with the remote
							// and make the file in-sync if they match
							ContentComparisonSyncInfoFilter comparator = new SyncInfoFilter.ContentComparisonSyncInfoFilter(false);
							if (resource.getType() == IResource.FILE && info.getRemote() != null) {
								if (comparator.compareContents((IFile)resource, info.getRemote(), Policy.subMonitorFor(monitor, 100))) {
									ICVSFile cvsFile = CVSWorkspaceRoot.getCVSFileFor((IFile)resource);
									cvsFile.checkedIn(null, false /* not a commit */);
								}
							}
						}
						monitor.done();
					}
				}
			}
		}, getMergeRule(diff), IResource.NONE, monitor);
	}

	protected void makeInSync(final IDiff diff, IProgressMonitor monitor) throws CoreException {
		run(new IWorkspaceRunnable() {
			public void run(IProgressMonitor monitor) throws CoreException {
				// Get the latest sync info for the file (i.e. not what is in the set).
				// We do this because the client may have modified the file since the
				// set was populated.
				IResource resource = getDiffTree().getResource(diff);
				if (resource.getType() != IResource.FILE)
					return;
				SyncInfo info = getSyncInfo(resource);
				ensureRemotesMatch(resource, diff, info);
				IResourceVariant remote = info.getRemote();
				RemoteFile file = (RemoteFile)remote;
				if (file != null)
					remote = file.getCachedHandle();
				
				if (info instanceof CVSSyncInfo) {
					CVSSyncInfo cvsInfo = (CVSSyncInfo) info;		
					cvsInfo.makeOutgoing(monitor);
					if (resource.getType() == IResource.FILE && info.getRemote() != null) {
						ICVSFile cvsFile = CVSWorkspaceRoot.getCVSFileFor((IFile)resource);
						if (remote != null && remote instanceof RemoteFile){
							cvsFile.setExecutable(((RemoteFile)remote).isExecutable());
							cvsFile.setTimeStamp(((RemoteFile) remote).getTimeStamp());
							cvsFile.setReadOnly(getReadOnly(cvsFile));
						}
						cvsFile.checkedIn(null , false /* not a commit */);
					}
				}
			}
		}, getMergeRule(diff), IResource.NONE, monitor);
	}
	
	protected boolean getReadOnly(ICVSFile cvsFile) {
		IResource resource = cvsFile.getIResource();
		RepositoryProvider provider = RepositoryProvider.getProvider(resource.getProject());
		if (provider instanceof CVSTeamProvider) {
			CVSTeamProvider ctp = (CVSTeamProvider) provider;
			try {
				return ctp.isWatchEditEnabled();
			} catch (CVSException e) {
				CVSUIPlugin.log(e);
			}
		}
		return false;
	}

	protected void ensureRemotesMatch(IResource resource, IDiff node, SyncInfo info) throws CVSException {
		IResourceVariant variant = info.getRemote();
		IFileRevision remote = getRemote(node);
		if (variant != null && remote != null && remote instanceof IFileRevision) {
			String ci1 = variant.getContentIdentifier();
			String ci2 = ((IFileRevision)remote).getContentIdentifier();
			if (!ci1.equals(ci2)) {
				throw new CVSException(NLS.bind(CVSUIMessages.WorkspaceSubscriberContext_0, resource.getFullPath().toString()));
			}
		}
	}

	private IFileRevision getRemote(IDiff node) {
		if (node == null) return null;
		if (node instanceof IThreeWayDiff) {
			IThreeWayDiff twd = (IThreeWayDiff) node;
			return getRemote(twd.getRemoteChange());
		}
		if (node instanceof IResourceDiff) {
			IResourceDiff rd = (IResourceDiff) node;
			return rd.getAfterState();
		}
		return null;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.team.core.mapping.MergeContext#merge(org.eclipse.team.core.diff.IDiffNode, boolean, org.eclipse.core.runtime.IProgressMonitor)
	 */
	public IStatus merge(IDiff delta, boolean force, IProgressMonitor monitor) throws CoreException {
		if (getMergeType() == ISynchronizationContext.TWO_WAY) {
			force = true;
		}
		// First, verify that the provided delta matches the current state
		// i.e. it is possible that a concurrent change has occurred
		IThreeWayDiff currentDiff = (IThreeWayDiff)getSubscriber().getDiff(getDiffTree().getResource(delta));
		if (currentDiff == null 
				|| currentDiff.getKind() == IDiff.NO_CHANGE 
				|| (currentDiff.getDirection() == IThreeWayDiff.OUTGOING && !force)) {
			// Seems like this one was already merged so return OK
			return Status.OK_STATUS;
		}
		if (!equals(currentDiff, (IThreeWayDiff)delta)) {
			throw new CVSException(NLS.bind(CVSUIMessages.CVSMergeContext_1, delta.getPath()));
		}
		try {
			monitor.beginTask(null, 100);
			IStatus status = super.merge(delta, force, Policy.subMonitorFor(monitor, 99));
			if (status.isOK()) {
				IResource resource = getDiffTree().getResource(delta);
				if (resource.getType() == IResource.FILE && !resource.exists()) {
					ICVSResource localResource = CVSWorkspaceRoot.getCVSResourceFor(resource);
					localResource.unmanage(Policy.subMonitorFor(monitor, 1));
				}
				pruneEmptyParents(new IDiff[] { delta });
			}
			return status;
		} finally {
			monitor.done();
		}
	}

	private boolean equals(IThreeWayDiff currentDiff, IThreeWayDiff otherDiff) {
		return currentDiff.getKind() == otherDiff.getKind() 
			&& currentDiff.getDirection() == otherDiff.getDirection();
	}

	private void pruneEmptyParents(IDiff[] deltas) throws CVSException {
		// TODO: A more explicit tie in to the pruning mechanism would be preferable.
		// i.e. I don't like referencing the option and visitor directly
		if (!CVSProviderPlugin.getPlugin().getPruneEmptyDirectories()) return;
		ICVSResource[] cvsResources = new ICVSResource[deltas.length];
		for (int i = 0; i < cvsResources.length; i++) {
			cvsResources[i] = CVSWorkspaceRoot.getCVSResourceFor(getDiffTree().getResource(deltas[i]));
		}
		new PruneFolderVisitor().visit(
			CVSWorkspaceRoot.getCVSFolderFor(ResourcesPlugin.getWorkspace().getRoot()),
			cvsResources);
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.team.core.mapping.provider.MergeContext#getMergeType()
	 */
	public int getMergeType() {
		return type;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.team.core.subscribers.SubscriberMergeContext#refresh(org.eclipse.core.resources.mapping.ResourceTraversal[], int, org.eclipse.core.runtime.IProgressMonitor)
	 */
	public void refresh(final ResourceTraversal[] traversals, int flags, IProgressMonitor monitor) throws CoreException {
		SubscriberDiffTreeEventHandler handler = getHandler();
		if (handler != null) {
			GroupProgressMonitor group = getGroup(monitor);
			if (group != null)
				handler.setProgressGroupHint(group.getGroup(), group.getTicks());
			handler.initializeIfNeeded();
			((CVSWorkspaceSubscriber)getSubscriber()).refreshWithContentFetch(traversals, monitor);
			runInBackground(new IWorkspaceRunnable() {
				public void run(IProgressMonitor monitor) throws CoreException {
					cacheContents(traversals, getDiffTree(), true, monitor);
				}
			});
		} else {
			super.refresh(traversals, flags, monitor);
			runInBackground(new IWorkspaceRunnable() {
				public void run(IProgressMonitor monitor) throws CoreException {
					cacheContents(traversals, getDiffTree(), false, monitor);
				}
			});
		}
	}
	
	private SubscriberDiffTreeEventHandler getHandler() {
		Object o = getAdapter(SubscriberDiffTreeEventHandler.class);
		if (o instanceof SubscriberDiffTreeEventHandler) {
			return (SubscriberDiffTreeEventHandler) o;
		}
		return null;
	}

	private GroupProgressMonitor getGroup(IProgressMonitor monitor) {
		if (monitor instanceof GroupProgressMonitor) {
			return (GroupProgressMonitor) monitor;
		}
		if (monitor instanceof ProgressMonitorWrapper) {
			ProgressMonitorWrapper wrapper = (ProgressMonitorWrapper) monitor;
			return getGroup(wrapper.getWrappedProgressMonitor());
		}
		return null;
	}
	
	protected void cacheContents(final ResourceTraversal[] traversals, IResourceDiffTree tree, boolean baseOnly, IProgressMonitor monitor) throws CVSException {
		// cache the base and remote contents
		// TODO: Refreshing and caching now takes 3 round trips.
		// OPTIMIZE: remote state and contents could be obtained in 1
		// OPTIMIZE: Based could be avoided if we always cached base locally
		ResourceMapping[] mappings = new ResourceMapping[] { new ResourceMapping() {
			public Object getModelObject() {
				return WorkspaceSubscriberContext.this;
			}
			public IProject[] getProjects() {
				return ResourcesPlugin.getWorkspace().getRoot().getProjects();
			}
			public ResourceTraversal[] getTraversals(ResourceMappingContext context, IProgressMonitor monitor) throws CoreException {
				return traversals;
			}
		    public boolean contains(ResourceMapping mapping) {
		    	return false;
		    }
			public String getModelProviderId() {
				return ModelProvider.RESOURCE_MODEL_PROVIDER_ID;
			}
		}};
		try {
			monitor.beginTask(null, 50);
			new CacheBaseContentsOperation(null, mappings, tree, true).run(Policy.subMonitorFor(monitor, 25));
			if (!baseOnly) {
				new CacheRemoteContentsOperation(null, mappings, tree).run(Policy.subMonitorFor(monitor, 25));
			}
		} catch (InvocationTargetException e) {
			throw CVSException.wrapException(e);
		} catch (InterruptedException e) {
			// Ignore
		} finally {
			monitor.done();
		}
	}
	
	public IStatus merge(IDiff[] deltas, boolean force, IProgressMonitor monitor) throws CoreException {
		try {
			if (deltas.length == 0) 
				return Status.OK_STATUS;
			String taskName = getMergeTaskName(deltas, force);
			monitor.beginTask(taskName, 100);
			monitor.setTaskName(taskName);
			cacheContents(getTraversals(deltas), getDiffTree(deltas), false, Policy.subMonitorFor(monitor, 30));
			return super.merge(deltas, force, Policy.subMonitorFor(monitor, 70));
		} finally {
			monitor.done();
		}
	}

	private String getMergeTaskName(IDiff[] deltas, boolean force) {
		if (force) {
			if (deltas.length == 1) {
				return NLS.bind(CVSUIMessages.WorkspaceSubscriberContext_1, getDiffTree().getResource(deltas[0]).getFullPath());
			}
			return NLS.bind(CVSUIMessages.WorkspaceSubscriberContext_2, new Integer(deltas.length));
		}
		if (deltas.length == 1) {
			return NLS.bind(CVSUIMessages.WorkspaceSubscriberContext_3, getDiffTree().getResource(deltas[0]).getFullPath());
		}
		return NLS.bind(CVSUIMessages.WorkspaceSubscriberContext_4, new Integer(deltas.length));
	}

	private ResourceTraversal[] getTraversals(IDiff[] deltas) {
		List result = new ArrayList();
		for (int i = 0; i < deltas.length; i++) {
			IDiff diff = deltas[i];
			IResource resource = ResourceDiffTree.getResourceFor(diff);
			if (resource != null) {
				result.add(resource);
			}
		}
		return new ResourceTraversal[] {
				new ResourceTraversal((IResource[]) result.toArray(new IResource[result.size()]), IResource.DEPTH_ZERO, IResource.NONE)
		};
	}

	private IResourceDiffTree getDiffTree(IDiff[] deltas) {
		ResourceDiffTree tree = new ResourceDiffTree();
		for (int i = 0; i < deltas.length; i++) {
			IDiff diff = deltas[i];
			tree.add(diff);
		}
		return tree;
	}
	
	protected void performReplace(IDiff diff, IProgressMonitor monitor) throws CoreException {
		IResource resource = ResourceDiffTree.getResourceFor(diff);
		if (resource.getType() == IResource.FILE){
			IFile file = (IFile) resource;
			ICVSFile mFile = CVSWorkspaceRoot.getCVSFileFor(file);
			try {
	            // The file may have been set as read-only by a previous checkout/update
	            if (mFile.isReadOnly()) mFile.setReadOnly(false);
	        } catch (CVSException e) {
	            // Just log and keep going
	            CVSProviderPlugin.log(e);
	        }
		}
		super.performReplace(diff, monitor);

	}
}