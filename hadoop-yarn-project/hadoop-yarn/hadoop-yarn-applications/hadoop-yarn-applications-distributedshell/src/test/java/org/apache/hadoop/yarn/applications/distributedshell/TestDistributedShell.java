/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.applications.distributedshell;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import junit.framework.Assert;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.util.JarFinder;
import org.apache.hadoop.util.Shell;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.server.MiniYARNCluster;
import org.apache.hadoop.yarn.server.nodemanager.NodeManager;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.ContainerManagerImpl;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fifo.FifoScheduler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestDistributedShell {

  private static final Log LOG =
      LogFactory.getLog(TestDistributedShell.class);

  protected static MiniYARNCluster yarnCluster = null;
  protected static Configuration conf = new YarnConfiguration();

  protected static String APPMASTER_JAR = JarFinder.getJar(ApplicationMaster.class);

  @BeforeClass
  public static void setup() throws Exception {
    LOG.info("Starting up YARN cluster");
    conf.setInt(YarnConfiguration.RM_SCHEDULER_MINIMUM_ALLOCATION_MB, 128);
    conf.setClass(YarnConfiguration.RM_SCHEDULER, 
        FifoScheduler.class, ResourceScheduler.class);
    if (yarnCluster == null) {
      yarnCluster = new MiniYARNCluster(
        TestDistributedShell.class.getSimpleName(), 1, 1, 1);
      yarnCluster.init(conf);
      yarnCluster.start();
      NodeManager  nm = yarnCluster.getNodeManager(0);
      waitForNMToRegister(nm);
      
      URL url = Thread.currentThread().getContextClassLoader().getResource("yarn-site.xml");
      if (url == null) {
        throw new RuntimeException("Could not find 'yarn-site.xml' dummy file in classpath");
      }
      Configuration yarnClusterConfig = yarnCluster.getConfig();
      yarnClusterConfig.set("yarn.application.classpath", new File(url.getPath()).getParent());
      //write the document to a buffer (not directly to the file, as that
      //can cause the file being written to get read -which will then fail.
      ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
      yarnClusterConfig.writeXml(bytesOut);
      bytesOut.close();
      //write the bytes to the file in the classpath
      OutputStream os = new FileOutputStream(new File(url.getPath()));
      os.write(bytesOut.toByteArray());
      os.close();
    }
    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      LOG.info("setup thread sleep interrupted. message=" + e.getMessage());
    }	
  }

  @AfterClass
  public static void tearDown() throws IOException {
    if (yarnCluster != null) {
      try {
        yarnCluster.stop();
      } finally {
        yarnCluster = null;
      }
    }
  }
  
  @Test(timeout=90000)
  public void testDSShell() throws Exception {

    String[] args = {
        "--jar",
        APPMASTER_JAR,
        "--num_containers",
        "2",
        "--shell_command",
        Shell.WINDOWS ? "dir" : "ls",
        "--master_memory",
        "512",
        "--master_vcores",
        "2",
        "--container_memory",
        "128",
        "--container_vcores",
        "1"
    };

    LOG.info("Initializing DS Client");
    final Client client = new Client(new Configuration(yarnCluster.getConfig()));
    boolean initSuccess = client.init(args);
    Assert.assertTrue(initSuccess);
    LOG.info("Running DS Client");
    final AtomicBoolean result = new AtomicBoolean(false);
    Thread t = new Thread() {
      public void run() {
        try {
          result.set(client.run());
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    };
    t.start();

    YarnClient yarnClient = YarnClient.createYarnClient();
    yarnClient.init(new Configuration(yarnCluster.getConfig()));
    yarnClient.start();
    String hostName = NetUtils.getHostname();
    boolean verified = false;
    while(!verified) {
      List<ApplicationReport> apps = yarnClient.getApplications();
      if (apps.size() == 0 ) {
        Thread.sleep(10);
        continue;
      }
      ApplicationReport appReport = apps.get(0);
      if (appReport.getHost().startsWith(hostName)
          && appReport.getRpcPort() == -1) {
        verified = true;
      }
      if (appReport.getYarnApplicationState() == YarnApplicationState.FINISHED) {
        break;
      }
    }
    Assert.assertTrue(verified);
    t.join();
    LOG.info("Client run completed. Result=" + result);
    Assert.assertTrue(result.get());

  }

  @Test(timeout=90000)
  public void testDSShellWithCustomLogPropertyFile() throws Exception {
    final File basedir =
        new File("target", TestDistributedShell.class.getName());
    final File tmpDir = new File(basedir, "tmpDir");
    tmpDir.mkdirs();
    final File customLogProperty = new File(tmpDir, "custom_log4j.properties");
    if (customLogProperty.exists()) {
      customLogProperty.delete();
    }
    if(!customLogProperty.createNewFile()) {
      Assert.fail("Can not create custom log4j property file.");
    }
    PrintWriter fileWriter = new PrintWriter(customLogProperty);
    // set the output to DEBUG level
    fileWriter.write("log4j.rootLogger=debug,stdout");
    fileWriter.close();
    String[] args = {
        "--jar",
        APPMASTER_JAR,
        "--num_containers",
        "3",
        "--shell_command",
        "echo",
        "--shell_args",
        "HADOOP",
        "--log_properties",
        customLogProperty.getAbsolutePath(),
        "--master_memory",
        "512",
        "--master_vcores",
        "2",
        "--container_memory",
        "128",
        "--container_vcores",
        "1"
    };

    //Before run the DS, the default the log level is INFO
    final Log LOG_Client =
        LogFactory.getLog(Client.class);
    Assert.assertTrue(LOG_Client.isInfoEnabled());
    Assert.assertFalse(LOG_Client.isDebugEnabled());
    final Log LOG_AM = LogFactory.getLog(ApplicationMaster.class);
    Assert.assertTrue(LOG_AM.isInfoEnabled());
    Assert.assertFalse(LOG_AM.isDebugEnabled());

    LOG.info("Initializing DS Client");
    final Client client =
        new Client(new Configuration(yarnCluster.getConfig()));
    boolean initSuccess = client.init(args);
    Assert.assertTrue(initSuccess);
    LOG.info("Running DS Client");
    boolean result = client.run();
    LOG.info("Client run completed. Result=" + result);
    Assert.assertTrue(verifyContainerLog(3, null, true, "DEBUG") > 10);
    //After DS is finished, the log level should be DEBUG
    Assert.assertTrue(LOG_Client.isInfoEnabled());
    Assert.assertTrue(LOG_Client.isDebugEnabled());
    Assert.assertTrue(LOG_AM.isInfoEnabled());
    Assert.assertTrue(LOG_AM.isDebugEnabled());
  }

  public void testDSShellWithCommands() throws Exception {

    String[] args = {
        "--jar",
        APPMASTER_JAR,
        "--num_containers",
        "2",
        "--shell_command",
        "\"echo output_ignored;echo output_expected\"",
        "--master_memory",
        "512",
        "--master_vcores",
        "2",
        "--container_memory",
        "128",
        "--container_vcores",
        "1"
    };

    LOG.info("Initializing DS Client");
    final Client client =
        new Client(new Configuration(yarnCluster.getConfig()));
    boolean initSuccess = client.init(args);
    Assert.assertTrue(initSuccess);
    LOG.info("Running DS Client");
    boolean result = client.run();
    LOG.info("Client run completed. Result=" + result);
    List<String> expectedContent = new ArrayList<String>();
    expectedContent.add("output_expected");
    verifyContainerLog(2, expectedContent, false, "");
  }

  @Test(timeout=90000)
  public void testDSShellWithInvalidArgs() throws Exception {
    Client client = new Client(new Configuration(yarnCluster.getConfig()));

    LOG.info("Initializing DS Client with no args");
    try {
      client.init(new String[]{});
      Assert.fail("Exception is expected");
    } catch (IllegalArgumentException e) {
      Assert.assertTrue("The throw exception is not expected",
          e.getMessage().contains("No args"));
    }

    LOG.info("Initializing DS Client with no jar file");
    try {
      String[] args = {
          "--num_containers",
          "2",
          "--shell_command",
          Shell.WINDOWS ? "dir" : "ls",
          "--master_memory",
          "512",
          "--container_memory",
          "128"
      };
      client.init(args);
      Assert.fail("Exception is expected");
    } catch (IllegalArgumentException e) {
      Assert.assertTrue("The throw exception is not expected",
          e.getMessage().contains("No jar"));
    }

    LOG.info("Initializing DS Client with no shell command");
    try {
      String[] args = {
          "--jar",
          APPMASTER_JAR,
          "--num_containers",
          "2",
          "--master_memory",
          "512",
          "--container_memory",
          "128"
      };
      client.init(args);
      Assert.fail("Exception is expected");
    } catch (IllegalArgumentException e) {
      Assert.assertTrue("The throw exception is not expected",
          e.getMessage().contains("No shell command"));
    }

    LOG.info("Initializing DS Client with invalid no. of containers");
    try {
      String[] args = {
          "--jar",
          APPMASTER_JAR,
          "--num_containers",
          "-1",
          "--shell_command",
          Shell.WINDOWS ? "dir" : "ls",
          "--master_memory",
          "512",
          "--container_memory",
          "128"
      };
      client.init(args);
      Assert.fail("Exception is expected");
    } catch (IllegalArgumentException e) {
      Assert.assertTrue("The throw exception is not expected",
          e.getMessage().contains("Invalid no. of containers"));
    }
    
    LOG.info("Initializing DS Client with invalid no. of vcores");
    try {
      String[] args = {
          "--jar",
          APPMASTER_JAR,
          "--num_containers",
          "2",
          "--shell_command",
          Shell.WINDOWS ? "dir" : "ls",
          "--master_memory",
          "512",
          "--master_vcores",
          "-2",
          "--container_memory",
          "128",
          "--container_vcores",
          "1"
      };
      client.init(args);
      Assert.fail("Exception is expected");
    } catch (IllegalArgumentException e) {
      Assert.assertTrue("The throw exception is not expected",
          e.getMessage().contains("Invalid virtual cores specified"));
    }
  }

  protected static void waitForNMToRegister(NodeManager nm)
      throws Exception {
    int attempt = 60;
    ContainerManagerImpl cm =
        ((ContainerManagerImpl) nm.getNMContext().getContainerManager());
    while (cm.getBlockNewContainerRequestsStatus() && attempt-- > 0) {
      Thread.sleep(2000);
    }
  }

  @Test(timeout=90000)
  public void testContainerLaunchFailureHandling() throws Exception {
    String[] args = {
      "--jar",
      APPMASTER_JAR,
      "--num_containers",
      "2",
      "--shell_command",
      Shell.WINDOWS ? "dir" : "ls",
      "--master_memory",
      "512",
      "--container_memory",
      "128"
    };

    LOG.info("Initializing DS Client");
    Client client = new Client(ContainerLaunchFailAppMaster.class.getName(),
      new Configuration(yarnCluster.getConfig()));
    boolean initSuccess = client.init(args);
    Assert.assertTrue(initSuccess);
    LOG.info("Running DS Client");
    boolean result = client.run();

    LOG.info("Client run completed. Result=" + result);
    Assert.assertFalse(result);

  }

  @Test(timeout=90000)
  public void testDebugFlag() throws Exception {
    String[] args = {
        "--jar",
        APPMASTER_JAR,
        "--num_containers",
        "2",
        "--shell_command",
        Shell.WINDOWS ? "dir" : "ls",
        "--master_memory",
        "512",
        "--master_vcores",
        "2",
        "--container_memory",
        "128",
        "--container_vcores",
        "1",
        "--debug"
    };

    LOG.info("Initializing DS Client");
    Client client = new Client(new Configuration(yarnCluster.getConfig()));
    Assert.assertTrue(client.init(args));
    LOG.info("Running DS Client");
    Assert.assertTrue(client.run());
  }

  private int verifyContainerLog(int containerNum,
      List<String> expectedContent, boolean count, String expectedWord) {
    File logFolder =
        new File(yarnCluster.getNodeManager(0).getConfig()
            .get(YarnConfiguration.NM_LOG_DIRS,
                YarnConfiguration.DEFAULT_NM_LOG_DIRS));

    File[] listOfFiles = logFolder.listFiles();
    int currentContainerLogFileIndex = -1;
    for (int i = listOfFiles.length - 1; i >= 0; i--) {
      if (listOfFiles[i].listFiles().length == containerNum + 1) {
        currentContainerLogFileIndex = i;
        break;
      }
    }
    Assert.assertTrue(currentContainerLogFileIndex != -1);
    File[] containerFiles =
        listOfFiles[currentContainerLogFileIndex].listFiles();

    int numOfWords = 0;
    for (int i = 0; i < containerFiles.length; i++) {
      for (File output : containerFiles[i].listFiles()) {
        if (output.getName().trim().contains("stdout")) {
          BufferedReader br = null;
          try {

            String sCurrentLine;

            br = new BufferedReader(new FileReader(output));
            int numOfline = 0;
            while ((sCurrentLine = br.readLine()) != null) {
              if (count) {
                if (sCurrentLine.contains(expectedWord)) {
                  numOfWords++;
                }
              } else if (output.getName().trim().equals("stdout")){
                Assert.assertEquals("The current is" + sCurrentLine,
                    expectedContent.get(numOfline), sCurrentLine.trim());
                numOfline++;
              }
            }

          } catch (IOException e) {
            e.printStackTrace();
          } finally {
            try {
              if (br != null)
                br.close();
            } catch (IOException ex) {
              ex.printStackTrace();
            }
          }
        }
      }
    }
    return numOfWords;
  }

}

