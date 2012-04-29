package com.inmobi.messaging.consumer.databus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Queue;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;

import com.inmobi.databus.DatabusConfig;
import com.inmobi.messaging.Message;

class PartitionReader {

  private static final Log LOG = LogFactory.getLog(PartitionReader.class);

  private final PartitionId partitionId;
  private final PartitionCheckpoint partitionCheckpoint;
  private final Queue<QueueEntry> buffer;
  private final Path collectorDir;
  private final FileSystem fs;
  private final String streamName;
  private Thread thread;
  private volatile boolean stopped;
  private Path currentFile;
  private long currentOffset;
  private long currentLineNum;
  private boolean skipLines = false;
  private boolean inited = false;
  private boolean gotoNext = false;

  PartitionReader(PartitionId partitionId, PartitionCheckpoint partition,
      DatabusConfig config, Queue<QueueEntry> buffer, String streamName) {
    this.partitionId = partitionId;
    this.partitionCheckpoint = partition;
    this.buffer = buffer;
    this.streamName = streamName;
    Path streamDir = new Path(config.getClusters().get(
        partitionId.getCluster()).getDataDir(), streamName);
    this.collectorDir = new Path(streamDir, partitionId.getCollector());
    try {
      this.fs = FileSystem.get(config.getClusters().get(
                  partitionId.getCluster()).getHadoopConf());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    LOG.info("Partition reader initialized with partitionId:" + partitionId +
    	      " checkPoint:" + partitionCheckpoint + " streamDir:" + streamDir + 
    	      " collectorDir:" + collectorDir);
  }

  public synchronized void start() {
    Runnable runnable = new Runnable() {
      @Override
      public void run() {
        while (!stopped && !thread.isInterrupted()) {
          long startTime = System.currentTimeMillis();
          try {
            execute();
            if (stopped || thread.isInterrupted())
              return;
          } catch (Exception e) {
            LOG.warn("Error in run", e);
          }
          long finishTime = System.currentTimeMillis();
          LOG.debug("Execution took ms : " + (finishTime - startTime));
          try {
            long sleep = 1000;
            if (sleep > 0) {
              LOG.info("Sleeping for " + sleep);
              Thread.sleep(sleep);
            }
          } catch (InterruptedException e) {
            LOG.warn("thread interrupted " + thread.getName(), e);
            return;
          }
        }
      }

    };
    thread = new Thread(runnable, this.partitionId.toString());
    LOG.info("Starting thread " + thread.getName());
    thread.start();
  }

  public void close() {
    stopped = true;
    LOG.info(Thread.currentThread().getName() + " stopped [" + stopped + "]");
  }

  void initializeCurrentFile() throws Exception {
    if (partitionCheckpoint.getFileName() == null) {
      currentFile = getFileList(null, fs);
    } else {
      currentFile = new Path(collectorDir, partitionCheckpoint.getFileName());
      if (partitionCheckpoint.getLineNum() > 0) {
        skipLines = true;
      }
    }
  }
  
  Path getCurrentFile() {
    return currentFile;
  }
  /**
   * Skip the number of lines passed.
   * 
   * @return the actual number of lines skipped.
   */
  private long skipLines(FSDataInputStream in, BufferedReader reader) 
      throws IOException {
    long numLines = partitionCheckpoint.getLineNum();
    long lineNum = 0;
    while (lineNum != numLines) {
      String line = readLine(in, reader);
      if (line == null) {
    	return lineNum;
      }
      lineNum++;
    }
    LOG.info("Skipped " + lineNum + " lines");
    if (lineNum != numLines) {
      LOG.warn("Skipped wrong number of lines");
    }
    return lineNum;
  }
  
  private String readLine(FSDataInputStream in, BufferedReader reader)
      throws IOException {
    String line = reader.readLine();
    if (line != null) {
      currentOffset = in.getPos();
      currentLineNum++;
    }
    return line;
  }

  private void resetCurrentFileSettings() {
    currentOffset = 0;
    currentLineNum = 0;
  }
  
  protected void execute() {
    try {
      // Get the file to read
      if (!inited) {
        LOG.info("Initialize the current file");
        initializeCurrentFile();
        resetCurrentFileSettings();
        inited = true;
      } else if (gotoNext) {
        LOG.debug("Get the next file");
        Path nextFile = getNextFile();
        if (nextFile == null) {
          LOG.info("No file to read");
          return;
        }
        currentFile = nextFile;
        resetCurrentFileSettings();
        gotoNext = false;
      } else {
        LOG.info("reading the same file");
      }
      
      LOG.info("Reading file " + currentFile + " from offset:" + currentOffset +
          " and lineNum:" + currentLineNum);
      FSDataInputStream in = fs.open(currentFile);
      in.seek(currentOffset);
      BufferedReader reader = new BufferedReader(new InputStreamReader(in));
      if (skipLines) {
        skipLines(in, reader);
        skipLines = false;
      }
      String line = readLine(in, reader);
      while (true) {
        if (line != null) {
          // add the data to queue
          byte[] data = Base64.decodeBase64(line);
          LOG.debug("Current LineNum: " + currentLineNum + " Current offset:" +
            currentOffset);
          buffer.add(new QueueEntry(new Message(
            ByteBuffer.wrap(data)), partitionId,
            new PartitionCheckpoint(currentFile.getName(), currentLineNum)));
        }
        if (line == null) {
          // if there is no data and we are reading from current scribe file,
          // do not switch to next file.
          String currentScribeFile = getCurrentScribeFile();
          LOG.debug("Current scribe file:" + currentScribeFile);
          if (currentScribeFile == null || 
              (!currentFile.getName().equals(currentScribeFile))) {
            LOG.debug("Going to next file");
            gotoNext = true;
          }
          break;
        }
        // Read next line
        line = readLine(in, reader);
      }
      reader.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private String getCurrentScribeFile() throws IOException {
    Path currentScribeFile = new Path(collectorDir, streamName + "_current");
    String currentFileName = null;
    if (fs.exists(currentScribeFile)) {
      FSDataInputStream in = fs.open(currentScribeFile);
      String line = new BufferedReader(new InputStreamReader(in)).readLine();
      if (line != null) {
        currentFileName = line.trim();
      }
      in.close();
    }
    return currentFileName;
  }

  private Path getNextFile() throws Exception {
    if (currentFile != null) {
      return getFileList(currentFile.getName(), fs);
    } else {
      LOG.warn("getNextFile called without currentFile");
      return null;
    }
  }

  private Path getFileList(String currentFileName, FileSystem fs)
      throws Exception {
    FileStatus[] files = fs.listStatus(collectorDir, new PathFilter() {
      @Override
      public boolean accept(Path p) {
        if (p.getName().endsWith("current")
            || p.getName().equals("scribe_stats")) {
          return false;
        }
        return true;
      }
    });
    if (files == null || files.length == 0) {
      LOG.info("No files in collector directory");
      return null;
    }
    String[] fileNames = new String[files.length];
    int i = 0;
    for (FileStatus s : files) {

      fileNames[i++] = s.getPath().getName();
    }

    Arrays.sort(fileNames);
    if (currentFileName == null) {
      return files[0].getPath();
    }
    int currentFileIndex;
    currentFileIndex = Arrays.binarySearch(fileNames, currentFileName);
    if (currentFileIndex == (files.length - 1)) {
      return null;
    }
    
    return files[++currentFileIndex].getPath();
  }

}
