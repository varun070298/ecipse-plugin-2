/*******************************************************************************
 * Copyright (c) 2006, 2011 Cognos Incorporated, IBM Corporation and others
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.eclipse.equinox.log.internal;

import java.io.PrintStream;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import org.eclipse.equinox.log.LogFilter;
import org.eclipse.equinox.log.SynchronousLogListener;
import org.eclipse.osgi.internal.baseadaptor.ArrayMap;
import org.osgi.framework.*;
import org.osgi.service.log.LogEntry;
import org.osgi.service.log.LogListener;

public class ExtendedLogReaderServiceFactory implements ServiceFactory<ExtendedLogReaderServiceImpl> {

	static final int MAX_RECURSIONS = 50;

	static final class LogTask implements Runnable {
		private final LogEntry logEntry;
		private final LogListener listener;

		LogTask(LogEntry logEntry, LogListener listener) {
			this.logEntry = logEntry;
			this.listener = listener;
		}

		public void run() {
			safeLogged(listener, logEntry);
		}
	}

	@SuppressWarnings("unchecked")
	private static final Enumeration<?> EMPTY_ENUMERATION = Collections.enumeration(Collections.EMPTY_LIST);

	static final LogFilter NULL_LOGGER_FILTER = new LogFilter() {
		public boolean isLoggable(Bundle b, String loggerName, int logLevel) {
			return true;
		}
	};

	private static final LogFilter[] ALWAYS_LOG = new LogFilter[0];

	private static PrintStream errorStream;

	private ArrayMap<LogListener, Object[]> listeners = new ArrayMap<LogListener, Object[]>(5);
	private LogFilter[] filters = null;

	private BasicReadWriteLock listenersLock = new BasicReadWriteLock();

	static boolean safeIsLoggable(LogFilter filter, Bundle bundle, String name, int level) {
		try {
			return filter.isLoggable(bundle, name, level);
		} catch (RuntimeException e) {
			// "listener.logged" calls user code and might throw an unchecked exception
			// we catch the error here to gather information on where the problem occurred.
			getErrorStream().println("LogFilter.isLoggable threw a non-fatal unchecked exception as follows:"); //$NON-NLS-1$
			e.printStackTrace(getErrorStream());
		} catch (LinkageError e) {
			// Catch linkage errors as these are generally recoverable but let other Errors propagate (see bug 222001)
			getErrorStream().println("LogFilter.isLoggable threw a non-fatal unchecked exception as follows:"); //$NON-NLS-1$
			e.printStackTrace(getErrorStream());
		}
		return false;
	}

	private static synchronized PrintStream getErrorStream() {
		if (errorStream == null)
			return System.err;

		return errorStream;
	}

	public static synchronized void setErrorStream(PrintStream ps) {
		errorStream = ps;
	}

	static void safeLogged(LogListener listener, LogEntry logEntry) {
		try {
			listener.logged(logEntry);
		} catch (RuntimeException e) {
			// "listener.logged" calls user code and might throw an unchecked exception
			// we catch the error here to gather information on where the problem occurred.
			getErrorStream().println("LogListener.logged threw a non-fatal unchecked exception as follows:"); //$NON-NLS-1$
			e.printStackTrace(getErrorStream());
		} catch (LinkageError e) {
			// Catch linkage errors as these are generally recoverable but let other Errors propagate (see bug 222001)
			getErrorStream().println("LogListener.logged threw a non-fatal unchecked exception as follows:"); //$NON-NLS-1$
			e.printStackTrace(getErrorStream());
		}
	}

	public ExtendedLogReaderServiceImpl getService(Bundle bundle, ServiceRegistration<ExtendedLogReaderServiceImpl> registration) {
		return new ExtendedLogReaderServiceImpl(this);
	}

	public void ungetService(Bundle bundle, ServiceRegistration<ExtendedLogReaderServiceImpl> registration, ExtendedLogReaderServiceImpl service) {
		service.shutdown();
	}

	boolean isLoggable(final Bundle bundle, final String name, final int level) {
		if (System.getSecurityManager() != null) {
			return AccessController.doPrivileged(new PrivilegedAction<Boolean>() {
				public Boolean run() {
					return isLoggablePrivileged(bundle, name, level);
				}
			});
		}
		return isLoggablePrivileged(bundle, name, level);
	}

	boolean isLoggablePrivileged(Bundle bundle, String name, int level) {
		int numNested = listenersLock.readLock();
		try {
			if (numNested == MAX_RECURSIONS)
				return false;
			if (filters == null)
				return false;

			if (filters == ALWAYS_LOG)
				return true;

			int filtersLength = filters.length;
			for (int i = 0; i < filtersLength; i++) {
				LogFilter filter = filters[i];
				if (safeIsLoggable(filter, bundle, name, level))
					return true;
			}
		} finally {
			listenersLock.readUnlock();
		}
		return false;
	}

	void log(final Bundle bundle, final String name, final Object context, final int level, final String message, final Throwable exception) {
		if (System.getSecurityManager() != null) {
			AccessController.doPrivileged(new PrivilegedAction<Object>() {
				public Object run() {
					logPrivileged(bundle, name, context, level, message, exception);
					return null;
				}
			});
		} else {
			logPrivileged(bundle, name, context, level, message, exception);
		}
	}

	void logPrivileged(Bundle bundle, String name, Object context, int level, String message, Throwable exception) {
		LogEntry logEntry = new ExtendedLogEntryImpl(bundle, name, context, level, message, exception);
		int numNested = listenersLock.readLock();
		try {
			if (numNested >= MAX_RECURSIONS)
				return;
			int size = listeners.size();
			for (int i = 0; i < size; i++) {
				Object[] listenerObjects = listeners.getValue(i);
				LogFilter filter = (LogFilter) listenerObjects[0];
				if (safeIsLoggable(filter, bundle, name, level)) {
					LogListener listener = listeners.getKey(i);
					SerializedTaskQueue taskQueue = (SerializedTaskQueue) listenerObjects[1];
					if (taskQueue != null) {
						taskQueue.put(new LogTask(logEntry, listener));
					} else {
						// log synchronously
						safeLogged(listener, logEntry);
					}
				}
			}
		} finally {
			listenersLock.readUnlock();
		}
	}

	void addLogListener(LogListener listener, LogFilter filter) {
		listenersLock.writeLock();
		try {
			Object[] listenerObjects = listeners.get(listener);
			if (listenerObjects == null) {
				// Only create a task queue for non-SynchronousLogListeners
				SerializedTaskQueue taskQueue = (listener instanceof SynchronousLogListener) ? null : new SerializedTaskQueue(listener.toString());
				listenerObjects = new Object[] {filter, taskQueue};
			} else if (filter != listenerObjects[0]) {
				// update the filter
				listenerObjects[0] = filter;
			}
			listeners.put(listener, listenerObjects);
			recalculateFilters();
		} finally {
			listenersLock.writeUnlock();
		}
	}

	private void recalculateFilters() {
		List<LogFilter> filtersList = new ArrayList<LogFilter>();
		int size = listeners.size();
		for (int i = 0; i < size; i++) {
			Object[] listenerObjects = listeners.getValue(i);
			LogFilter filter = (LogFilter) listenerObjects[0];
			if (filter == NULL_LOGGER_FILTER) {
				filters = ALWAYS_LOG;
				return;
			}
			filtersList.add(filter);
		}

		if (filtersList.isEmpty())
			filters = null;

		filters = filtersList.toArray(new LogFilter[filtersList.size()]);
	}

	void removeLogListener(LogListener listener) {
		listenersLock.writeLock();
		try {
			listeners.remove(listener);
			recalculateFilters();
		} finally {
			listenersLock.writeUnlock();
		}
	}

	Enumeration<?> getLog() {
		return EMPTY_ENUMERATION;
	}
}
