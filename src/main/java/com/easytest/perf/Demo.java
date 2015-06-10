package com.easytest.perf;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.contains;
import static com.google.common.collect.Iterables.getOnlyElement;
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_AMI_QUERY;
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_CC_AMI_QUERY;
import static org.jclouds.compute.config.ComputeServiceProperties.TIMEOUT_SCRIPT_COMPLETE;
import static org.jclouds.compute.options.TemplateOptions.Builder.overrideLoginCredentials;
import static org.jclouds.compute.options.TemplateOptions.Builder.overrideLoginUser;
import static org.jclouds.compute.options.TemplateOptions.Builder.runScript;
import static org.jclouds.compute.predicates.NodePredicates.TERMINATED;
import static org.jclouds.compute.predicates.NodePredicates.SUSPENDED;
import static org.jclouds.compute.predicates.NodePredicates.RUNNING;
import static org.jclouds.compute.predicates.NodePredicates.inGroup;
import static org.jclouds.scriptbuilder.domain.Statements.exec;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.jclouds.ContextBuilder;
import org.jclouds.apis.ApiMetadata;
import org.jclouds.apis.Apis;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.RunScriptOnNodesException;
import org.jclouds.compute.domain.ExecResponse;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.ec2.domain.InstanceType;
import org.jclouds.enterprise.config.EnterpriseConfigurationModule;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.providers.ProviderMetadata;
import org.jclouds.providers.Providers;
import org.jclouds.scriptbuilder.domain.Statement;
import org.jclouds.scriptbuilder.statements.login.AdminAccess;
import org.jclouds.scriptbuilder.statements.login.UserAdd;
import org.jclouds.sshj.config.SshjSshClientModule;

import com.google.common.base.Charsets;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.inject.Module;

import org.jclouds.compute.domain.Image;
import org.jclouds.compute.domain.ComputeMetadata;
import org.jclouds.compute.domain.OsFamily;
import org.jclouds.compute.functions.Sha512Crypt;


public class Demo {

   public static enum Action {
      ADD, RUN, EXEC, TURNON, TURNOFF, DESTROY, LISTIMAGES, LISTNODES;
   }
   
   public static final Map<String, ApiMetadata> allApis = Maps.uniqueIndex(Apis.viewableAs(ComputeServiceContext.class),
        Apis.idFunction());
   
   public static final Map<String, ProviderMetadata> appProviders = Maps.uniqueIndex(Providers.viewableAs(ComputeServiceContext.class),
        Providers.idFunction());
   
   public static final Set<String> allKeys = ImmutableSet.copyOf(Iterables.concat(appProviders.keySet(), allApis.keySet()));
   
   public static int PARAMETERS = 5;
   public static String INVALID_SYNTAX = "Invalid number of parameters. Syntax is: provider identity credential groupName (add|exec|run|destroy)";

   public static void main(String[] args) {
      if (args.length < PARAMETERS)
         throw new IllegalArgumentException(INVALID_SYNTAX);

      String provider = args[0];
      String identity = args[1];
      String credential = args[2];
      String groupName = args[3];
      Action action = Action.valueOf(args[4].toUpperCase());
      boolean providerIsGCE = provider.equalsIgnoreCase("google-compute-engine");

      if (action == Action.EXEC && args.length < PARAMETERS + 1)
         throw new IllegalArgumentException("please quote the command to exec as the last parameter");
      String command = (action == Action.EXEC) ? args[5] : "echo hello";

      // For GCE, the credential parameter is the path to the private key file
      if (providerIsGCE)
         credential = getPrivateKeyFromFile(credential);

      if (action == Action.RUN && args.length < PARAMETERS + 1)
         throw new IllegalArgumentException("please pass the local file to run as the last parameter");
      File file = null;
      if (action == Action.RUN || action == Action.TURNOFF || action == Action.TURNON) {
         file = new File(args[5]);
         if (!file.exists())
            throw new IllegalArgumentException("file must exist! " + file);
      }
      
      String minRam = System.getProperty("minRam");
      String loginUser = System.getProperty("loginUser", "toor");
      
      // note that you can check if a provider is present ahead of time
      checkArgument(contains(allKeys, provider), "provider %s not in supported list: %s", provider, allKeys);

      LoginCredentials login = (action != Action.DESTROY) ? getLoginForCommandExecution(action) : null;

      ComputeService compute = initComputeService(provider, identity, credential);

      try {
         switch (action) {
         case ADD:
            System.out.printf(">> adding node to group %s%n", groupName);

            // Default template chooses the smallest size on an operating system
            // that tested to work with java, which tends to be Ubuntu or CentOS
            TemplateBuilder templateBuilder = compute.templateBuilder().locationId("ap-southeast-1").hardwareId(InstanceType.M1_MEDIUM);

            if (providerIsGCE)
               templateBuilder.osFamily(OsFamily.CENTOS);
            
            // If you want to up the ram and leave everything default, you can 
            // just tweak minRam
            if (minRam != null)
               templateBuilder.minRam(Integer.parseInt(minRam));
            
            
            // 1> Below AdminAccess.standard() solution is passed test
            // note this will create a user with the same name as you on the
            // node. ex. you can connect via ssh publicip
            Statement bootInstructions = AdminAccess.standard();

            
            // to run commands as root, we use the runScript option in the template.
            if(provider.equalsIgnoreCase("virtualbox"))
               templateBuilder.options(overrideLoginUser(loginUser).runScript(bootInstructions));
            else
               templateBuilder.options(runScript(bootInstructions));
                       
            NodeMetadata node = getOnlyElement(compute.createNodesInGroup(groupName, 1, templateBuilder.build()));
            System.out.printf("<< node %s: %s%n", node.getId(),
                  concat(node.getPrivateAddresses(), node.getPublicAddresses()));
            	/*
            * init to run docker daemon installation  
            	 */
            Map<? extends NodeMetadata, ExecResponse> responses = compute.runScriptOnNodesMatching(//
                    inGroup(groupName), // predicate used to select nodes
                    exec("sudo wget -qO- https://get.docker.com/ | sh"), // what you actually intend to run
                    overrideLoginCredentials(login) // use my local user & ssh key
                          .runAsRoot(false) // don't attempt to run as root (sudo)
                          .wrapInInitScript(false));// run command directly

              for (Entry<? extends NodeMetadata, ExecResponse> response : responses.entrySet()) {
                 System.out.printf("<< node %s: %s%n", response.getKey().getId(),
                       concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()));
                 System.out.printf("<<     %s%n", response.getValue());
              	}

            break;      
         case RUN:
            System.out.printf(">> running [%s] on group %s as %s%n", file, groupName, login.identity);

            // when running a sequence of commands, you probably want to have jclouds use the default behavior, 
            // which is to fork a background process.
            Map<? extends NodeMetadata, ExecResponse> responserun = compute.runScriptOnNodesMatching(//
                  inGroup(groupName),
                  Files.toString(file, Charsets.UTF_8), // passing in a string with the contents of the file
                  overrideLoginCredentials(login)
                        .runAsRoot(false)
                        .wrapInInitScript(true) // do not display script content when from jclouds API return result
                        .nameTask("_" + file.getName().replaceAll("\\..*", ""))); // ensuring task name isn't
                                                       // the same as the file so status checking works properly

            for (Entry<? extends NodeMetadata, ExecResponse> response : responserun.entrySet()) {
               System.out.printf("<< node %s: %s%n", response.getKey().getId(),
                     concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()));
               System.out.printf("<<     %s%n", response.getValue());
            	}
            break;
         case TURNOFF:
        	 	/*
        	 	 * before to do turn off the VMs, do stop and remove docker container
        	 	 */
        	 	System.out.printf(">> turnoff [%s] on group %s as %s%n", file, groupName, login.identity);
        	 	Map<? extends NodeMetadata, ExecResponse> stopandremove = compute.runScriptOnNodesMatching(//
                     inGroup(groupName),
                     Files.toString(file, Charsets.UTF_8), // passing in a string with the contents of the file
                     overrideLoginCredentials(login)
                           .runAsRoot(false)
                           .wrapInInitScript(true) // do not display script content when from jclouds API return result
                           .nameTask("_" + file.getName().replaceAll("\\..*", ""))); // ensuring task name isn't
                                                          // the same as the file so status checking works properly
        	 		for (Entry<? extends NodeMetadata, ExecResponse> response : stopandremove.entrySet()) {
                 System.out.printf("<< node %s: %s%n", response.getKey().getId(),
                       concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()));
                 System.out.printf("<<     %s%n", response.getValue());
              		}
        	 		System.out.printf(">> turn off nodes in group %s%n", groupName);
        	 		// you can use predicates to select which nodes you wish to turn off.
        	 		Set<? extends NodeMetadata> turnoffs = compute.suspendNodesMatching(//
                   Predicates.<NodeMetadata> and(RUNNING, inGroup(groupName)));
        	 		System.out.printf("<< turnoff nodes %s%n", turnoffs);
        	 		break;
         case TURNON:
        	 		System.out.printf(">> turnon [%s] on group %s as %s%n", file, groupName, login.identity);
             
             	// you can use predicates to select which nodes you wish to turn on.
             	Set<? extends NodeMetadata> turnons = compute.resumeNodesMatching(//
                   Predicates.<NodeMetadata> and(SUSPENDED, inGroup(groupName)));
             	System.out.printf("<< turnon nodes %s%n", turnons);
             		/*
             	 * after nodes are turned on, to start new docker container
             		 */
             	// TODO
             	Map<? extends NodeMetadata, ExecResponse> turnonrun = compute.runScriptOnNodesMatching(//
                     inGroup(groupName),
                     Files.toString(file, Charsets.UTF_8), // passing in a string with the contents of the file
                     overrideLoginCredentials(login)
                           .runAsRoot(false)
                           .wrapInInitScript(true) // do not display script content when from jclouds API return result
                           .nameTask("_" + file.getName().replaceAll("\\..*", ""))); // ensuring task name isn't
                                                          // the same as the file so status checking works properly
             	for (Entry<? extends NodeMetadata, ExecResponse> response : turnonrun.entrySet()) {
                    System.out.printf("<< node %s: %s%n", response.getKey().getId(),
                          concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()));
                    System.out.printf("<<     %s%n", response.getValue());
                 	}
             	break;
         case DESTROY:
            System.out.printf(">> destroying nodes in group %s%n", groupName);
            // you can use predicates to select which nodes you wish to destroy.
            Set<? extends NodeMetadata> destroyed = compute.destroyNodesMatching(//
                  Predicates.<NodeMetadata> and(not(TERMINATED), inGroup(groupName)));
            System.out.printf("<< destroyed nodes %s%n", destroyed);
            break;
         case LISTIMAGES:
            Set<? extends Image> images = compute.listImages();
            System.out.printf(">> No of images %d%n", images.size());
            for (Image img : images) {
               System.out.println(">>>>  " + img);
            }
            break;
         case LISTNODES:
            Set<? extends ComputeMetadata> nodes = compute.listNodes();
            System.out.printf(">> No of nodes/instances %d%n", nodes.size());
            for (ComputeMetadata nodeData : nodes) {
               System.out.println(">>>>  " + nodeData);
            }
            break;
		default:
			break;
         }
      } catch (RunNodesException e) {
         System.err.println("error adding node to group " + groupName + ": " + e.getMessage());
         error = 1;
      } catch (RunScriptOnNodesException e) {
         System.err.println("error executing " + command + " on group " + groupName + ": " + e.getMessage());
         error = 1;
      } catch (Exception e) {
         System.err.println("error: " + e.getMessage());
         error = 1;
      } finally {
         compute.getContext().close();
         System.exit(error);
      }
   }

   private static String getPrivateKeyFromFile(String filename) {
      try {
         return Files.toString(new File(filename), Charsets.UTF_8);
      } catch (IOException e) {
         System.err.println("Exception reading private key from '%s': " + filename);
         e.printStackTrace();
         System.exit(1);
         return null;
      }
   }

   static int error = 0;

   private static ComputeService initComputeService(String provider, String identity, String credential) {

      // example of specific properties, in this case optimizing image list to
      // only amazon supplied
      Properties properties = new Properties();
      properties.setProperty(PROPERTY_EC2_AMI_QUERY, "owner-id=137112412989;state=available;image-type=machine");
      properties.setProperty(PROPERTY_EC2_CC_AMI_QUERY, "");
      long scriptTimeout = TimeUnit.MILLISECONDS.convert(20, TimeUnit.MINUTES);
      properties.setProperty(TIMEOUT_SCRIPT_COMPLETE, scriptTimeout + "");

      // example of injecting a ssh implementation
      Iterable<Module> modules = ImmutableSet.<Module> of(
            new SshjSshClientModule(),
            new SLF4JLoggingModule(),
            new EnterpriseConfigurationModule());

      ContextBuilder builder = ContextBuilder.newBuilder(provider)
                                             .credentials(identity, credential)
                                             .modules(modules)
                                             .overrides(properties);
                                             
      System.out.printf(">> initializing %s%n", builder.getApiMetadata());

      return builder.buildView(ComputeServiceContext.class).getComputeService();
   }

   private static LoginCredentials getLoginForCommandExecution(Action action) {
      try {
        String user = System.getProperty("user.name");
        String privateKey = Files.toString(
            new File(System.getProperty("user.home") + "/.ssh/id_rsa"), UTF_8);
        return LoginCredentials.builder().
            user(user).privateKey(privateKey).build();
      } catch (Exception e) {
         System.err.println("error reading ssh key " + e.getMessage());
         System.exit(1);
         return null;
      }
	  // return LoginCredentials.builder().user("agent").password("agent").build();
   }

}
