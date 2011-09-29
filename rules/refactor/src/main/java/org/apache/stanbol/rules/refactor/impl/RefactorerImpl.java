/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.stanbol.rules.refactor.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Dictionary;

import org.apache.clerezza.rdf.core.Graph;
import org.apache.clerezza.rdf.core.MGraph;
import org.apache.clerezza.rdf.core.UriRef;
import org.apache.clerezza.rdf.core.access.TcManager;
import org.apache.clerezza.rdf.core.access.WeightedTcProvider;
import org.apache.clerezza.rdf.core.impl.SimpleMGraph;
import org.apache.clerezza.rdf.core.serializedform.Serializer;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.stanbol.ontologymanager.ontonet.api.DuplicateIDException;
import org.apache.stanbol.ontologymanager.ontonet.api.ONManager;
import org.apache.stanbol.ontologymanager.ontonet.api.ontology.OntologyScope;
import org.apache.stanbol.ontologymanager.ontonet.api.ontology.OntologyScopeFactory;
import org.apache.stanbol.ontologymanager.ontonet.api.ontology.OntologySpaceFactory;
import org.apache.stanbol.ontologymanager.ontonet.api.ontology.ScopeRegistry;
import org.apache.stanbol.ontologymanager.ontonet.api.ontology.UnmodifiableOntologySpaceException;
import org.apache.stanbol.ontologymanager.ontonet.api.session.DuplicateSessionIDException;
import org.apache.stanbol.ontologymanager.ontonet.api.session.Session;
import org.apache.stanbol.ontologymanager.ontonet.api.session.SessionManager;
import org.apache.stanbol.ontologymanager.ontonet.impl.io.ClerezzaOntologyStorage;
import org.apache.stanbol.owl.transformation.JenaToClerezzaConverter;
import org.apache.stanbol.owl.transformation.OWLAPIToClerezzaConverter;
import org.apache.stanbol.rules.base.api.NoSuchRecipeException;
import org.apache.stanbol.rules.base.api.Recipe;
import org.apache.stanbol.rules.base.api.Rule;
import org.apache.stanbol.rules.base.api.RuleStore;
import org.apache.stanbol.rules.base.api.util.RuleList;
import org.apache.stanbol.rules.manager.arqextention.CreatePropertyURIStringFromLabel;
import org.apache.stanbol.rules.manager.arqextention.CreateStandardLabel;
import org.apache.stanbol.rules.manager.arqextention.CreateURI;
import org.apache.stanbol.rules.refactor.api.Refactorer;
import org.apache.stanbol.rules.refactor.api.RefactoringException;
import org.apache.stanbol.rules.refactor.api.util.URIGenerator;
import org.osgi.service.component.ComponentContext;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.RDFXMLOntologyFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.util.OWLOntologyMerger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QueryExecutionFactory;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.query.Syntax;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.reasoner.Reasoner;
import com.hp.hpl.jena.sparql.function.FunctionRegistry;
import com.hp.hpl.jena.sparql.pfunction.PropertyFunctionRegistry;
import com.hp.hpl.jena.update.UpdateAction;

class ForwardChainingRefactoringGraph {

    private MGraph inputGraph;
    private Graph outputGraph;

    public ForwardChainingRefactoringGraph(MGraph inputGraph, Graph outputGraph) {
        this.inputGraph = inputGraph;
        this.outputGraph = outputGraph;
    }

    public MGraph getInputGraph() {
        return inputGraph;
    }

    public Graph getOutputGraph() {
        return outputGraph;
    }

}

/**
 * The RefactorerImpl is the concrete implementation of the Refactorer interface defined in the KReS APIs. A
 * SemionRefacter is able to perform ontology refactorings and mappings.
 * 
 * @author andrea.nuzzolese
 * 
 */

@Component(immediate = true, metatype = true)
@Service(Refactorer.class)
public class RefactorerImpl implements Refactorer {

    public static final String _AUTO_GENERATED_ONTOLOGY_IRI_DEFAULT = "http://kres.iksproject.eu/semion/autoGeneratedOntology";
    public static final String _HOST_NAME_AND_PORT_DEFAULT = "localhost:8080";
    public static final String _REFACTORING_SCOPE_DEFAULT = "refactoring";
    public static final String _REFACTORING_SESSION_ID_DEFAULT = "http://kres.iksproject.eu/session/refactoring";
//    public static final String _REFACTORING_SPACE_DEFAULT = "http://kres.iksproject.eu/space/refactoring";

    @Property(value = _AUTO_GENERATED_ONTOLOGY_IRI_DEFAULT)
    public static final String AUTO_GENERATED_ONTOLOGY_IRI = "org.apache.stanbol.reengineer.default";

    @Property(value = _HOST_NAME_AND_PORT_DEFAULT)
    public static final String HOST_NAME_AND_PORT = "host.name.port";

    @Property(_REFACTORING_SCOPE_DEFAULT)
    public static final String REFACTORING_SCOPE = "org.apache.stanbol.ontologymanager.scope.refactoring";

    @Property(value = _REFACTORING_SESSION_ID_DEFAULT)
    public static final String REFACTORING_SESSION_ID = "org.apache.stanbol.ontlogymanager.session.refactoring";

//    @Property(value = _REFACTORING_SPACE_DEFAULT)
//    public static final String REFACTORING_SPACE = "org.apache.stanbol.reengineer.space.refactoring";

    private IRI defaultRefactoringIRI;

    private IRI kReSSessionID;

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference
    protected ONManager onManager;

    private String refactoringScopeID;

//    private IRI refactoringSpaceIRI;

    @Reference
    protected RuleStore ruleStore;

    private OntologyScope scope;

    @Reference
    protected Serializer serializer;

    @Reference
    protected TcManager tcManager;

    @Reference
    protected WeightedTcProvider weightedTcProvider;

    /**
     * This default constructor is <b>only</b> intended to be used by the OSGI environment with Service
     * Component Runtime support.
     * <p>
     * DO NOT USE to manually create instances - the RefactorerImpl instances do need to be configured! YOU
     * NEED TO USE
     * {@link #RefactorerImpl(WeightedTcProvider, Serializer, TcManager, ONManager, SemionManager, RuleStore, Reasoner, Dictionary)}
     * or its overloads, to parse the configuration and then initialise the rule store if running outside a
     * OSGI environment.
     */
    public RefactorerImpl() {

    }

    /**
     * Basic constructor to be used if outside of an OSGi environment. Invokes default constructor.
     * 
     * @param weightedTcProvider
     * @param serializer
     * @param tcManager
     * @param onManager
     * @param semionManager
     * @param ruleStore
     * @param kReSReasoner
     * @param configuration
     */
    public RefactorerImpl(WeightedTcProvider weightedTcProvider,
                          Serializer serializer,
                          TcManager tcManager,
                          ONManager onManager, /* SemionManager semionManager, */
                          RuleStore ruleStore,
                          Dictionary<String,Object> configuration) {
        this();
        this.weightedTcProvider = weightedTcProvider;
        this.serializer = serializer;
        this.tcManager = tcManager;
        this.onManager = onManager;
        // this.semionManager = semionManager;
        this.ruleStore = ruleStore;
        activate(configuration);
    }

    /**
     * Used to configure an instance within an OSGi container.
     * 
     * @throws IOException
     */
    @SuppressWarnings("unchecked")
    @Activate
    protected void activate(ComponentContext context) throws IOException {
        log.info("in " + getClass() + " activate with context " + context);
        if (context == null) {
            throw new IllegalStateException("No valid" + ComponentContext.class + " parsed in activate!");
        }
        activate((Dictionary<String,Object>) context.getProperties());
    }

    protected void activate(Dictionary<String,Object> configuration) {
        String refactoringSessionID = (String) configuration.get(REFACTORING_SESSION_ID);
        if (refactoringSessionID == null) refactoringSessionID = _REFACTORING_SESSION_ID_DEFAULT;
        String refactoringScopeID = (String) configuration.get(REFACTORING_SCOPE);
        if (refactoringScopeID == null) refactoringScopeID = _REFACTORING_SCOPE_DEFAULT;
//        String refactoringSpaceID = (String) configuration.get(REFACTORING_SPACE);
//        if (refactoringSpaceID == null) refactoringSpaceID = _REFACTORING_SPACE_DEFAULT;
        String defaultRefactoringID = (String) configuration.get(AUTO_GENERATED_ONTOLOGY_IRI);
        if (defaultRefactoringID == null) defaultRefactoringID = _AUTO_GENERATED_ONTOLOGY_IRI_DEFAULT;
        String hostPort = (String) configuration.get(HOST_NAME_AND_PORT);
        if (hostPort == null) hostPort = _HOST_NAME_AND_PORT_DEFAULT;

        kReSSessionID = IRI.create(refactoringSessionID);
//        refactoringScopeID = IRI.create("http://" + hostPort + "/kres/ontology/" + refactoringScopeID);
//        refactoringSpaceIRI = IRI.create(refactoringSpaceID);
        defaultRefactoringIRI = IRI.create(defaultRefactoringID);

        SessionManager kReSSessionManager = onManager.getSessionManager();

        Session kReSSession = kReSSessionManager.getSession(kReSSessionID);

        if (kReSSession == null) {
            try {
                kReSSession = kReSSessionManager.createSession(kReSSessionID);
            } catch (DuplicateSessionIDException e) {
                log.error("SemionRefactorer : a KReS session for reengineering seems already existing", e);
            }
        }

        kReSSessionID = kReSSession.getID();

        OntologyScopeFactory ontologyScopeFactory = onManager.getOntologyScopeFactory();

        ScopeRegistry scopeRegistry = onManager.getScopeRegistry();

        OntologySpaceFactory ontologySpaceFactory = onManager.getOntologySpaceFactory();

        scope = null;
        try {
            log.info("Semion DBExtractor : created scope with IRI " + REFACTORING_SCOPE);

            scope = ontologyScopeFactory.createOntologyScope(refactoringScopeID, null);

            scopeRegistry.registerScope(scope);
        } catch (DuplicateIDException e) {
            log.info("Semion DBExtractor : already existing scope for IRI " + REFACTORING_SCOPE);
            scope = onManager.getScopeRegistry().getScope(refactoringScopeID);
        }

        try {
            scope.addSessionSpace(ontologySpaceFactory.createSessionOntologySpace(refactoringScopeID),
                kReSSession.getID());
        } catch (UnmodifiableOntologySpaceException e) {
            log.error("Failed to create session space", e);
        }

        scopeRegistry.setScopeActive(refactoringScopeID, true);

        // semionManager.registerRefactorer(this);

        PropertyFunctionRegistry.get().put("http://www.stlab.istc.cnr.it/semion/function#createURI",
            CreateURI.class);
        FunctionRegistry.get().put("http://www.stlab.istc.cnr.it/semion/function#createLabel",
            CreateStandardLabel.class);
        FunctionRegistry.get().put("http://www.stlab.istc.cnr.it/semion/function#propString",
            CreatePropertyURIStringFromLabel.class);

        log.info("Activated KReS Semion Refactorer");
    }

    @Deactivate
    protected void deactivate(ComponentContext context) {
        log.info("in " + getClass() + " deactivate with context " + context);

        SessionManager kReSSessionManager = onManager.getSessionManager();
        kReSSessionManager.destroySession(kReSSessionID);
        // semionManager.unregisterRefactorer();
        this.weightedTcProvider = null;
        this.serializer = null;
        this.tcManager = null;
        this.onManager = null;
        this.ruleStore = null;
    }

    private ForwardChainingRefactoringGraph forwardChainingOperation(String query, MGraph mGraph) {

        Graph graph = kReSCoreOperation(query, mGraph);

        mGraph.addAll(graph);

        return new ForwardChainingRefactoringGraph(mGraph, graph);
    }

    @Override
    public MGraph getRefactoredDataSet(UriRef uriRef) {

        return weightedTcProvider.getMGraph(uriRef);
    }

    private Graph kReSCoreOperation(String query, MGraph mGraph) {

        /*
         * 
         * Graph constructedGraph = null; try { ConstructQuery constructQuery = (ConstructQuery)
         * QueryParser.getInstance() .parse(query); constructedGraph = tcManager.executeSparqlQuery(
         * constructQuery, mGraph);
         * 
         * } catch (ParseException e) { log.error(e.getMessage()); } catch (NoQueryEngineException e) {
         * log.error(e.getMessage()); }
         * 
         * return constructedGraph;
         */

        Model model = JenaToClerezzaConverter.clerezzaMGraphToJenaModel(mGraph);

        Query sparqlQuery = QueryFactory.create(query, Syntax.syntaxARQ);
        QueryExecution qexec = QueryExecutionFactory.create(sparqlQuery, model);

        return JenaToClerezzaConverter.jenaModelToClerezzaMGraph(qexec.execConstruct()).getGraph();

    }

    @Override
    public void ontologyRefactoring(IRI refactoredOntologyIRI, IRI datasetURI, IRI recipeIRI) throws RefactoringException,
                                                                                             NoSuchRecipeException {

        OWLOntology refactoredOntology = null;

        ClerezzaOntologyStorage ontologyStorage = onManager.getOntologyStore();

        Recipe recipe;
        try {
            recipe = ruleStore.getRecipe(recipeIRI);

            RuleList kReSRuleList = recipe.getkReSRuleList();

            OWLOntologyManager ontologyManager = OWLManager.createOWLOntologyManager();

            String fingerPrint = "";
            for (Rule kReSRule : kReSRuleList) {
                String sparql = kReSRule.toSPARQL();
                OWLOntology refactoredDataSet = ontologyStorage
                        .sparqlConstruct(sparql, datasetURI.toString());

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try {
                    ontologyManager.saveOntology(refactoredDataSet, new RDFXMLOntologyFormat(), out);
                    if (refactoredOntologyIRI == null) {
                        ByteArrayOutputStream fpOut = new ByteArrayOutputStream();
                        fingerPrint += URIGenerator.createID("", fpOut.toByteArray());
                    }

                } catch (OWLOntologyStorageException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }

                ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());

                try {
                    ontologyManager.loadOntologyFromOntologyDocument(in);
                } catch (OWLOntologyCreationException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }

            }

            if (refactoredOntologyIRI == null) {
                refactoredOntologyIRI = IRI.create(URIGenerator.createID("urn://", fingerPrint.getBytes()));
            }
            OWLOntologyMerger merger = new OWLOntologyMerger(ontologyManager);

            try {

                refactoredOntology = merger.createMergedOntology(ontologyManager, refactoredOntologyIRI);

                ontologyStorage.store(refactoredOntology);

            } catch (OWLOntologyCreationException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

        } catch (NoSuchRecipeException e1) {
            log.error("SemionRefactorer : No Such recipe in the KReS Rule Store", e1);
            throw e1;
        }

        if (refactoredOntology == null) {
            throw new RefactoringException();
        }
    }

    @Override
    public OWLOntology ontologyRefactoring(OWLOntology inputOntology, IRI recipeIRI) throws RefactoringException,
                                                                                    NoSuchRecipeException {
        OWLOntology refactoredOntology = null;

        // JenaToOwlConvert jenaToOwlConvert = new JenaToOwlConvert();

        // OntModel ontModel =
        // jenaToOwlConvert.ModelOwlToJenaConvert(inputOntology, "RDF/XML");

        Recipe recipe;
        try {
            recipe = ruleStore.getRecipe(recipeIRI);

            RuleList kReSRuleList = recipe.getkReSRuleList();
            log.info("RULE LIST SIZE : " + kReSRuleList.size());

            MGraph unionMGraph = new SimpleMGraph();

            MGraph mGraph = OWLAPIToClerezzaConverter.owlOntologyToClerezzaMGraph(inputOntology);

            for (Rule kReSRule : kReSRuleList) {
                String sparql = kReSRule.toSPARQL();
                log.info("SPARQL : " + sparql);

                Graph constructedGraph = null;

                switch (kReSRule.getExpressiveness()) {
                    case KReSCore:
                        constructedGraph = kReSCoreOperation(sparql, mGraph);
                        break;
                    case ForwardChaining:
                        ForwardChainingRefactoringGraph forwardChainingRefactoringGraph = forwardChainingOperation(
                            sparql, mGraph);
                        constructedGraph = forwardChainingRefactoringGraph.getOutputGraph();
                        mGraph = forwardChainingRefactoringGraph.getInputGraph();
                        break;
                    case Reflexive:
                        constructedGraph = kReSCoreOperation(sparql, unionMGraph);
                        break;
                    case SPARQLConstruct:
                        constructedGraph = kReSCoreOperation(sparql, mGraph);
                        break;
                    case SPARQLDelete:
                        constructedGraph = sparqlUpdateOperation(sparql, unionMGraph);
                        break;
                    case SPARQLDeleteData:
                        constructedGraph = sparqlUpdateOperation(sparql, unionMGraph);
                        break;
                    default:
                        break;
                }

                if (constructedGraph != null) {
                    unionMGraph.addAll(constructedGraph);
                }

            }

            refactoredOntology = OWLAPIToClerezzaConverter.clerezzaMGraphToOWLOntology(unionMGraph);

        } catch (NoSuchRecipeException e1) {
            e1.printStackTrace();
            log.error("SemionRefactorer : No Such recipe in the KReS Rule Store", e1);
            throw e1;
        }

        if (refactoredOntology == null) {
            throw new RefactoringException();
        } else {
            return refactoredOntology;
        }
    }

    @Override
    public OWLOntology ontologyRefactoring(OWLOntology inputOntology, Recipe recipe) throws RefactoringException {
        OWLOntology refactoredOntology = null;

        // JenaToOwlConvert jenaToOwlConvert = new JenaToOwlConvert();

        // OntModel ontModel =
        // jenaToOwlConvert.ModelOwlToJenaConvert(inputOntology, "RDF/XML");

        // OWLOntologyManager manager = OWLManager.createOWLOntologyManager();

        RuleList ruleList = recipe.getkReSRuleList();
        log.info("RULE LIST SIZE : " + ruleList.size());

        // OWLOntologyManager ontologyManager = OWLManager.createOWLOntologyManager();
        // OWLOntologyManager ontologyManager2 = OWLManager.createOWLOntologyManager();

        MGraph unionMGraph = new SimpleMGraph();

        MGraph mGraph = OWLAPIToClerezzaConverter.owlOntologyToClerezzaMGraph(inputOntology);

        for (Rule kReSRule : ruleList) {
            String sparql = kReSRule.toSPARQL();
            log.info("SPARQL : " + sparql);

            Graph constructedGraph = null;

            switch (kReSRule.getExpressiveness()) {
                case KReSCore:
                    constructedGraph = kReSCoreOperation(sparql, mGraph);
                    break;
                case ForwardChaining:
                    ForwardChainingRefactoringGraph forwardChainingRefactoringGraph = forwardChainingOperation(
                        sparql, mGraph);
                    constructedGraph = forwardChainingRefactoringGraph.getOutputGraph();
                    mGraph = forwardChainingRefactoringGraph.getInputGraph();
                    break;
                case Reflexive:
                    constructedGraph = kReSCoreOperation(sparql, unionMGraph);
                    break;
                case SPARQLConstruct:
                    constructedGraph = kReSCoreOperation(sparql, mGraph);
                    break;
                case SPARQLDelete:
                    constructedGraph = sparqlUpdateOperation(sparql, unionMGraph);
                    break;
                case SPARQLDeleteData:
                    constructedGraph = sparqlUpdateOperation(sparql, unionMGraph);
                    break;
                default:
                    break;
            }

            if (constructedGraph != null) {
                unionMGraph.addAll(constructedGraph);
            }

        }

        refactoredOntology = OWLAPIToClerezzaConverter.clerezzaMGraphToOWLOntology(unionMGraph);

        if (refactoredOntology == null) {
            throw new RefactoringException();
        } else {
            return refactoredOntology;
        }
    }

    private Graph sparqlUpdateOperation(String query, MGraph mGraph) {
        Model model = JenaToClerezzaConverter.clerezzaMGraphToJenaModel(mGraph);
        UpdateAction.parseExecute(query, model);
        return JenaToClerezzaConverter.jenaModelToClerezzaMGraph(model).getGraph();
    }

}
