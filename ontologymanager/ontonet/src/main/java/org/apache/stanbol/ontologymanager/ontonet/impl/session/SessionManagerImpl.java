package org.apache.stanbol.ontologymanager.ontonet.impl.session;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.stanbol.ontologymanager.ontonet.api.ontology.OntologyScope;
import org.apache.stanbol.ontologymanager.ontonet.api.ontology.ScopeRegistry;
import org.apache.stanbol.ontologymanager.ontonet.api.ontology.SessionOntologySpace;
import org.apache.stanbol.ontologymanager.ontonet.api.session.DuplicateSessionIDException;
import org.apache.stanbol.ontologymanager.ontonet.api.session.Session;
import org.apache.stanbol.ontologymanager.ontonet.api.session.SessionIDGenerator;
import org.apache.stanbol.ontologymanager.ontonet.api.session.SessionManager;
import org.apache.stanbol.ontologymanager.ontonet.api.session.NonReferenceableSessionException;
import org.apache.stanbol.ontologymanager.ontonet.api.session.SessionEvent;
import org.apache.stanbol.ontologymanager.ontonet.api.session.SessionListener;
import org.apache.stanbol.ontologymanager.ontonet.api.session.Session.State;
import org.apache.stanbol.ontologymanager.ontonet.api.session.SessionEvent.OperationType;
import org.apache.stanbol.ontologymanager.ontonet.impl.io.ClerezzaOntologyStorage;
import org.apache.stanbol.ontologymanager.store.api.PersistenceStore;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * Calls to <code>getSessionListeners()</code> return a {@link Set} of
 * listeners.
 * 
 * TODO: implement storage (using persistence layer).
 * 
 * @author alessandro
 * 
 */
public class SessionManagerImpl implements SessionManager {

	private Map<IRI, Session> sessionsByID;

	protected Set<SessionListener> listeners;

	protected Logger log = LoggerFactory.getLogger(getClass());

	protected SessionIDGenerator idgen;

	protected ScopeRegistry scopeRegistry;

	/*
	 * 
	 * The local ClerezzaOntologyStorage has been changed with the global Stanbol component
	 * PersistenceStore.
	 * 
	 */
	//protected ClerezzaOntologyStorage store;
	protected PersistenceStore persistenceStore;

	public SessionManagerImpl(IRI baseIri, ScopeRegistry scopeRegistry, PersistenceStore persistenceStore) {
		idgen = new TimestampedSessionIDGenerator(baseIri);
		listeners = new HashSet<SessionListener>();
		sessionsByID = new HashMap<IRI, Session>();
		this.scopeRegistry = scopeRegistry;
		this.persistenceStore = persistenceStore;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * eu.iksproject.kres.api.manager.session.SessionManager#addSessionListener
	 * (eu.iksproject.kres.api.manager.session.SessionListener)
	 */
	@Override
	public void addSessionListener(SessionListener listener) {
		listeners.add(listener);
	}


	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * eu.iksproject.kres.api.manager.session.SessionManager#clearSessionListeners
	 * ()
	 */
	@Override
	public void clearSessionListeners() {
		listeners.clear();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * eu.iksproject.kres.api.manager.session.SessionManager#createSession()
	 */
	@Override
	public Session createSession() {
		Set<IRI> exclude = getRegisteredSessionIDs();
		Session session = null;
		while (session == null)
			try {
				session = createSession(idgen.createSessionID(exclude));
			} catch (DuplicateSessionIDException e) {
				exclude.add(e.getDulicateID());
				continue;
			}
		return session;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * eu.iksproject.kres.api.manager.session.SessionManager#createSession(org
	 * .semanticweb.owlapi.model.IRI)
	 */
	@Override
	public Session createSession(IRI sessionID)
			throws DuplicateSessionIDException {
		if (sessionsByID.containsKey(sessionID))
			throw new DuplicateSessionIDException(sessionID);
		Session session = new SessionImpl(sessionID);
		addSession(session);
		fireSessionCreated(session);
		return session;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * eu.iksproject.kres.api.manager.session.SessionManager#destroySession(
	 * org.semanticweb.owlapi.model.IRI)
	 */
	@Override
	public void destroySession(IRI sessionID) {
		try {
			Session ses = sessionsByID.get(sessionID);
			ses.close();
			if (ses instanceof SessionImpl)
				((SessionImpl) ses).state = State.ZOMBIE;
			// Make session no longer referenceable
			removeSession(ses);
			fireSessionDestroyed(ses);
		} catch (NonReferenceableSessionException e) {
			log.warn(
					"KReS :: tried to kick a dead horse on session "
							+ sessionID
							+ " which was already in a zombie state.", e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * eu.iksproject.kres.api.manager.session.SessionManager#getSession(org.
	 * semanticweb.owlapi.model.IRI)
	 */
	@Override
	public Session getSession(IRI sessionID) {
		return sessionsByID.get(sessionID);
	}

	@Override
	public Set<IRI> getRegisteredSessionIDs() {
		return sessionsByID.keySet();
	}

	protected void fireSessionCreated(Session session) {
		SessionEvent e;
		try {
			e = new SessionEvent(session, OperationType.CREATE);
			for (SessionListener l : listeners)
				l.sessionChanged(e);
		} catch (Exception e1) {
			LoggerFactory
					.getLogger(getClass())
					.error(
							"KReS :: Exception occurred while attempting to fire session creation event for session "
									+ session.getID(), e1);
			return;
		}

	}

	protected void fireSessionDestroyed(Session session) {
		SessionEvent e;
		try {
			e = new SessionEvent(session, OperationType.KILL);
			for (SessionListener l : listeners)
				l.sessionChanged(e);
		} catch (Exception e1) {
			LoggerFactory
					.getLogger(getClass())
					.error(
							"KReS :: Exception occurred while attempting to fire session destruction event for session "
									+ session.getID(), e1);
			return;
		}

	}

	protected void addSession(Session session) {
		sessionsByID.put(session.getID(), session);
	}

	protected void removeSession(Session session) {
		IRI id = session.getID();
		Session s2 = sessionsByID.get(id);
		if (session == s2)
			sessionsByID.remove(id);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * eu.iksproject.kres.api.manager.session.SessionManager#getSessionListeners
	 * ()
	 */
	@Override
	public Collection<SessionListener> getSessionListeners() {
		return listeners;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * TODO : optimize with indexing.
	 * 
	 * @see
	 * eu.iksproject.kres.api.manager.session.SessionManager#getSessionSpaces
	 * (org.semanticweb.owlapi.model.IRI)
	 */
	@Override
	public Set<SessionOntologySpace> getSessionSpaces(IRI sessionID)
			throws NonReferenceableSessionException {
		Set<SessionOntologySpace> result = new HashSet<SessionOntologySpace>();
		// Brute force search
		for (OntologyScope scope : scopeRegistry
				.getRegisteredScopes()) {
			SessionOntologySpace space = scope.getSessionSpace(sessionID);
			if (space != null)
				result.add(space);
		}
		return result;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * eu.iksproject.kres.api.manager.session.SessionManager#removeSessionListener
	 * (eu.iksproject.kres.api.manager.session.SessionListener)
	 */
	@Override
	public void removeSessionListener(SessionListener listener) {
		listeners.remove(listener);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * TODO : storage not implemented yet
	 * 
	 * @see
	 * eu.iksproject.kres.api.manager.session.SessionManager#storeSession(org
	 * .semanticweb.owlapi.model.IRI, java.io.OutputStream)
	 */
	@Override
	public void storeSession(IRI sessionID, OutputStream out)
			throws NonReferenceableSessionException, OWLOntologyStorageException {
		/*
		 * For each gession space in the session save all the ontologies contained in the space.
		 */
		for (SessionOntologySpace so : getSessionSpaces(sessionID)){
			for (OWLOntology owlOntology : so.getOntologies()){
				
				/*
				 * As the store saves ontology as string serializations first it is needed to convert the ontology content
				 * to a string (the UTF-8 charset is used) and then it is possible to save the ontology specifying to the storage
				 * the ontology ID.
				 */
				ByteArrayOutputStream outStream = new ByteArrayOutputStream();
				
				owlOntology.getOWLOntologyManager().saveOntology(owlOntology, outStream);
				
				String ontologyID = owlOntology.getOntologyID().getOntologyIRI().toString();
				
				String ontologyString;
				try {
					ontologyString = outStream.toString("UTF-8");
					persistenceStore.saveOntology(ontologyString, ontologyID, "UTF-8");
				} catch (UnsupportedEncodingException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				
				
			}
		}
				
	}

}
