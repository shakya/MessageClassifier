package controllers;

import akka.actor.*;
import akka.cluster.Cluster;
import akka.contrib.pattern.ClusterClient;
import akka.contrib.pattern.ClusterSingletonManager;
import akka.contrib.pattern.ClusterSingletonPropsFactory;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import mc.messages.TextMessage;
import mc.messages.store.MessageStore;
import mc.messages.store.ObjectDBMsgStore;
import models.com.mc.workers.Work;
import models.com.mc.configs.ClassifiersConfig;
import models.com.mc.workers.*;
import play.data.DynamicForm;
import play.mvc.Controller;
import play.mvc.Result;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;


import java.util.*;

import static akka.pattern.Patterns.ask;
import static play.data.Form.form;

public class MainController extends Controller {

    private static ActorRef frontend = null;
    static int  n=0;
    private volatile static Address systemAdress;
    volatile static int noOfWorkers=0;

    //private static Initializer initializer=null;
    private volatile static boolean isInitilized = false;

    public static Result index() {

    	// Create DB instance
    	MessageStore objectDBMsgStore = new ObjectDBMsgStore();

    	// Get total count - ZERO based indexing used in DB
    	int storeSize = (int)objectDBMsgStore.size();
        String total = String.valueOf(storeSize);

        // Get other values
        String spam = String.valueOf(objectDBMsgStore.getMessageCountOf("spam", "spam"));
        String male = String.valueOf(objectDBMsgStore.getMessageCountOf("gender", "male"));

        List<String> recentMessages=new ArrayList<String>();

        if(storeSize<10)
        {
        	// Retrieve all messages
        	for(int index = storeSize-1; index>-1; index--){
	        	recentMessages.add(objectDBMsgStore.get(index).getMessage());        	
	        }
        }
        else
        {
	        // Retrieve last 10 messages
	        for(int index = storeSize-1; index>storeSize-11; index--){
	        	recentMessages.add(objectDBMsgStore.get(index).getMessage());        	
	        }
        }

        List<List<String>> contexts=new ArrayList<List<String>>();
        
		// Get list of context
		List<String> contextCategories = objectDBMsgStore.getCategoriesOf("context");
		
		for (String category : contextCategories) {
			List<String> categoryDetails = new ArrayList<String>();
			// Category type
			categoryDetails.add(category);
			// Message count
			categoryDetails.add(String.valueOf(objectDBMsgStore.getMessageCountOf("context", category)));			
			// Add to contexts
			contexts.add(categoryDetails);
		}

        List<List<String>> languages=new ArrayList<List<String>>();
        
		// Get list of languages
		List<String> languageCategories = objectDBMsgStore.getCategoriesOf("language");

		for (String category : languageCategories) {
			List<String> categoryDetails = new ArrayList<String>();
			// Category type
			categoryDetails.add(category);
			// Message count
			categoryDetails.add(String.valueOf(objectDBMsgStore.getMessageCountOf("language", category)));
			// Add to languages
			languages.add(categoryDetails);
		}

        return ok(views.html.index.render(String.valueOf(isInitilized),total,spam,male,recentMessages,contexts,languages));
    }

    public static Result configure() {
        return ok(views.html.configure.render(String.valueOf(isInitilized),String.valueOf(noOfWorkers)));
    }

    public static Result updateConfigurations() {
        DynamicForm dynamicForm = form().bindFromRequest();
        String s="";
        s=dynamicForm.get("context_input");
        if(!s.equals("")){
            ClassifiersConfig.setCONTEXT_SERVICE(s);
        }

        s=dynamicForm.get("gender_input");
        if(!s.equals("")){
            ClassifiersConfig.setGENDER_SERVICE(s);
        }
        s=dynamicForm.get("language_input");
        if(!s.equals("")){
            ClassifiersConfig.setLANGUAGE_SERVICE(s);
        }
        s=dynamicForm.get("spam_input");
        if(!s.equals("")){
            ClassifiersConfig.setSPAM_SERVICE(s);
        }
        s=dynamicForm.get("timeout_input");
        if(!s.equals("")){
            ClassifiersConfig.setCLASSIFIER_SERVICE_TIMEOUT(Integer.parseInt(s));
        }
        s=dynamicForm.get("depoy_context_input");
        ClassifiersConfig.setDEPLOY_CONTEXT_CLASSIFIER(Boolean.parseBoolean(s));
        s=dynamicForm.get("depoy_gender_input");
        ClassifiersConfig.setDEPLOY_GENDER_CLASSIFIER(Boolean.parseBoolean(s));
        s=dynamicForm.get("depoy_language_input");
        ClassifiersConfig.setDEPLOY_LANGUAGE_CLASSIFIER(Boolean.parseBoolean(s));
        s=dynamicForm.get("deploy_spam_input");
        ClassifiersConfig.setDEPLOY_SPAM_CLASSIFIER(Boolean.parseBoolean(s));

        return ok(views.html.success.render("Hello Prabhath"));
    }

    public static Result addWorker(String msg){
        if(isInitilized){
            startWorker(systemAdress);
            noOfWorkers++;

        }
        return ok("true");
    }

    public static Result init(String msg){
       if(!isInitilized){
           //initializer=new Initializer();
           isInitilized = true;
           noOfWorkers++;
           try {
               MainController.initilize();
               //initializer.main(null);
           } catch (InterruptedException e) {
               e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
           }
       }
        return ok("started=true");
    }

    public static Result shutdown(String msg){
        if(isInitilized){
            shutDown();
            isInitilized=false;
        }
        return ok("stopped=true");
    }

    //http://localhost:9000/actor/Hi
    public static Result process(String msg){
        if(isInitilized){
            n=+1;
            Work message = new Work(msg, nextWorkId());

          // Master.Work work = new Master.Work(nextWorkId(), n);
            //initializer.sendMessage(message);
            MainController.sendMessage(message);
           return ok(msg+" added to the queue");
        }else{
            return ok("Should initialized first");
        }
    }

    private static String nextWorkId() {
        return UUID.randomUUID().toString();
    }



    /////

    private static String systemName = "Workers";
    private static FiniteDuration workTimeout = Duration.create(10, "seconds");
    //private static ActorRef frontend;

    private  static void initilize() throws InterruptedException {
        Address joinAddress = startBackend(null, "backend");
        systemAdress=joinAddress;
        Thread.sleep(5000);
//        startBackend(joinAddress, "backend");
        startWorker(joinAddress);
//        Thread.sleep(5000);
        startFrontend(joinAddress);
    }

    public static Address startBackend(Address joinAddress, String role) {
        Config conf = ConfigFactory.parseString("akka.cluster.roles=[" + role + "] ").
                withFallback(ConfigFactory.load());
        ActorSystem system = ActorSystem.create(systemName, conf);
        Address realJoinAddress =
                (joinAddress == null) ? Cluster.get(system).selfAddress() : joinAddress;
        Cluster.get(system).join(realJoinAddress);

        system.actorOf(ClusterSingletonManager.defaultProps("active",
                PoisonPill.getInstance(), role, new ClusterSingletonPropsFactory() {
            public Props create(Object handOverData) {
                return Master.props(workTimeout);
            }
        }), "master");
        System.out.println(realJoinAddress);
        return realJoinAddress;
    }

    public static void startWorker(Address contactAddress) {
        ActorSystem system = ActorSystem.create(systemName);
        Set<ActorSelection> initialContacts = new HashSet<ActorSelection>();
        String s=contactAddress + "/user/receptionist";
        initialContacts.add(system.actorSelection(s));
        ActorRef clusterClient = system.actorOf(ClusterClient.defaultProps(initialContacts),
                "clusterClient");
        system.actorOf(Worker.props(clusterClient, Props.create(WorkExecutor.class)), "worker");
    }

    public static void startFrontend(Address joinAddress) {
        ActorSystem system = ActorSystem.create(systemName);
        Cluster.get(system).join(joinAddress);
        frontend = system.actorOf(Props.create(Frontend.class), "frontend");
        //system.actorOf(Props.create(WorkProducer.class, frontend), "producer");
        system.actorOf(Props.create(MessageCollector.class), "consumer");
    }

    public static void sendMessage(Work message){
        frontend.tell(message,null);
    }


    public static void shutDown(){
        ActorSystem system = ActorSystem.create(systemName);
        system.shutdown();
    }







}
