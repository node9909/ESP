/*
 * ESP Copyright (C) 2013 - 2014 Burton Alexander
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 * 
 */
package com.github.mrstampy.esp.multiconnectionsocket;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import javolution.util.FastList;
import javolution.util.FastMap;

import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IoSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.functions.Action1;

import com.github.mrstampy.esp.multiconnectionsocket.event.AbstractMultiConnectionEvent;
import com.github.mrstampy.esp.multiconnectionsocket.subscription.MultiConnectionSubscriptionRequest;

// TODO: Auto-generated Javadoc
/**
 * Abstract {@link IoHandler} implementation to receive subscriptions and
 * publish {@link AbstractMultiConnectionEvent}s on a socket.
 * 
 * @author burton
 * 
 * @param <E>
 *          enum representing the event type
 * @param <AMCS>
 *          the {@link AbstractMultiConnectionSocket} implementation
 * @param <MCSR>
 *          the {@link MultiConnectionSubscriptionRequest}
 */
public abstract class AbstractSubscriptionHandlerAdapter<E extends Enum<E>, AMCS extends AbstractMultiConnectionSocket<?>, MCSR extends MultiConnectionSubscriptionRequest<E>>
		extends IoHandlerAdapter {

	private static final Logger log = LoggerFactory.getLogger(AbstractSubscriptionHandlerAdapter.class);

	private AMCS socket;

	private Map<E, List<HostPort>> subscriptions = new FastMap<E, List<HostPort>>();
	private Map<HostPort, IoSession> sessions = new FastMap<HostPort, IoSession>();

	private ReentrantReadWriteLock requestsLock = new ReentrantReadWriteLock(true);
	private WriteLock writeLock = requestsLock.writeLock();
	private ReadLock readLock = requestsLock.readLock();

	/**
	 * Instantiates a new abstract subscription handler adapter.
	 *
	 * @param socket the socket
	 */
	protected AbstractSubscriptionHandlerAdapter(AMCS socket) {
		setSocket(socket);
	}

	/**
	 * Implement to deal with subscription requests & any other message types the
	 * subclass must deal with.
	 *
	 * @param session the session
	 * @param message the message
	 * @throws Exception the exception
	 * @see AbstractSubscriptionHandlerAdapter#subscribe(IoSession,
	 *      MultiConnectionSubscriptionRequest)
	 */
	public abstract void messageReceived(IoSession session, Object message) throws Exception;

	/* (non-Javadoc)
	 * @see org.apache.mina.core.service.IoHandlerAdapter#sessionClosed(org.apache.mina.core.session.IoSession)
	 */
	public void sessionClosed(IoSession session) throws Exception {
		HostPort hostPort = createHostPort(session);

		log.info("Disconnecting socket on {}", hostPort);

		writeLock.lock();
		try {
			for (Entry<E, List<HostPort>> entry : subscriptions.entrySet()) {
				List<HostPort> list = entry.getValue();
				list.remove(hostPort);
			}
			sessions.remove(hostPort);
		} finally {
			writeLock.unlock();
		}
	}

	/**
	 * Sends the specified {@link AbstractMultiConnectionEvent} to all interested
	 * subscribers.
	 *
	 * @param event the event
	 * @see AbstractMultiConnectionSocket
	 * @see AbstractSocketConnector
	 */
	public void sendMultiConnectionEvent(AbstractMultiConnectionEvent<E> event) {
		Observable.just(event).subscribe(new Action1<AbstractMultiConnectionEvent<E>>() {

			@Override
			public void call(AbstractMultiConnectionEvent<E> t1) {
				List<HostPort> list = null;
				readLock.lock();
				try {
					List<HostPort> tmp = subscriptions.get(t1.getEventType());
					if (tmp != null) list = new FastList<HostPort>(tmp);
				} finally {
					readLock.unlock();
				}
				
				if (list == null) return;
				for (HostPort hp : list) {
					sendMultiConnectionEvent(hp, t1);
				}
			}
		});
	}

	/**
	 * Subscribe.
	 *
	 * @param session the session
	 * @param message the message
	 */
	protected void subscribe(IoSession session, MCSR message) {
		HostPort hostPort = createHostPort(session);

		writeLock.lock();
		try {
			E[] types = message.getEventTypes();
			if (types == null) {
				log.error("No types to subscribe to for message {}", message);
				return;
			}
			for (int i = 0; i < types.length; i++) {
				E type = types[i];
				List<HostPort> hps = subscriptions.get(type);
				if (hps == null) {
					hps = new FastList<HostPort>();
					subscriptions.put(type, hps);
				}
				if (!hps.contains(hostPort)) hps.add(hostPort);
			}
			sessions.put(hostPort, session);
		} finally {
			writeLock.unlock();
		}

		log.info("{} subscribed to {}", hostPort, message);
	}

	private void sendMultiConnectionEvent(HostPort key, AbstractMultiConnectionEvent<E> event) {
		IoSession session = sessions.get(key);

		log.trace("Sending event {} to {}", event.getEventType(), key);

		session.write(event);
	}

	/**
	 * Gets the socket.
	 *
	 * @return the socket
	 */
	public AMCS getSocket() {
		return socket;
	}

	/**
	 * Sets the socket.
	 *
	 * @param socket the new socket
	 */
	protected void setSocket(AMCS socket) {
		this.socket = socket;
	}

	/**
	 * Creates the host port.
	 *
	 * @param session the session
	 * @return the host port
	 */
	protected HostPort createHostPort(IoSession session) {
		InetSocketAddress remote = (InetSocketAddress) session.getRemoteAddress();

		return new HostPort(remote.getHostName(), remote.getPort());
	}

}
