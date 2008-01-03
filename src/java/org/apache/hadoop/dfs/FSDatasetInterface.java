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
package org.apache.hadoop.dfs;


import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;




import org.apache.hadoop.util.DiskChecker.DiskErrorException;

/**
 * This is an interface for the underlying storage that stores blocks for
 * a data node. 
 * Examples are the FSDataset (which stores blocks on dirs)  and 
 * SimulatedFSDataset (which simulates data).
 *
 */
public interface FSDatasetInterface {
  
  
  /**
   * Returns the length of the metadata file of the specified block
   * @param b - the block for which the metadata length is desired
   * @return the length of the metadata file for the specified block.
   * @throws IOException
   */
  public long getMetaDataLength(Block b) throws IOException;
  
  /**
   * This class provides the input stream and length of the metadata
   * of a block
   *
   */
  static class MetaDataInputStream extends FilterInputStream {
    MetaDataInputStream(InputStream stream, long len) {
      super(stream);
      length = len;
    }
    private long length;
    
    public long getLength() {
      return length;
    }
  }
  
  /**
   * Returns metaData of block b as an input stream (and its length)
   * @param b - the block
   * @return the metadata input stream; 
   * @throws IOException
   */
  public MetaDataInputStream getMetaDataInputStream(Block b)
        throws IOException;
  
  /**
   * Does the meta file exist for this block?
   * @param b - the block
   * @return true of the metafile for specified block exits
   * @throws IOException
   */
  public boolean metaFileExists(Block b) throws IOException;
    
  /**
   * Returns the total space (in bytes) used by dfs datanode
   * @return  the total space used by dfs datanode
   * @throws IOException
   */  
  public long getDfsUsed() throws IOException;
    
  /**
   * Returns total capacity (in bytes) of storage (used and unused)
   * @return  total capacity of storage (used and unused)
   * @throws IOException
   */
  public long getCapacity() throws IOException;

  /**
   * Returns the amount of free storage space (in bytes)
   * @return The amount of free storage space
   * @throws IOException
   */
  public long getRemaining() throws IOException;

  /**
   * Returns the specified block's on-disk length (excluding metadata)
   * @param b
   * @return   the specified block's on-disk length (excluding metadta)
   * @throws IOException
   */
  public long getLength(Block b) throws IOException;
     
  /**
   * Returns an input stream to read the contents of the specified block
   * @param b
   * @return an input stream to read the contents of the specified block
   * @throws IOException
   */
  public InputStream getBlockInputStream(Block b) throws IOException;
  
  /**
   * Returns an input stream at specified offset of the specified block
   * @param b
   * @param seekOffset
   * @return an input stream to read the contents of the specified block,
   *  starting at the offset
   * @throws IOException
   */
  public InputStream getBlockInputStream(Block b, long seekOffset)
            throws IOException;

     /**
      * 
      * This class contains the output streams for the data and checksum
      * of a block
      *
      */
     static class BlockWriteStreams {
      OutputStream dataOut;
      OutputStream checksumOut;
      BlockWriteStreams(OutputStream dOut, OutputStream cOut) {
        dataOut = dOut;
        checksumOut = cOut;
      }
      
    }
    
  /**
   * Creates the block and returns output streams to write data and CRC
   * @param b
   * @return a BlockWriteStreams object to allow writing the block data
   *  and CRC
   * @throws IOException
   */
  public BlockWriteStreams writeToBlock(Block b) throws IOException;

  /**
   * Finalizes the block previously opened for writing using writeToBlock.
   * The block size is what is in the parameter b and it must match the amount
   *  of data written
   * @param b
   * @throws IOException
   */
  public void finalizeBlock(Block b) throws IOException;

  /**
   * Returns the block report - the full list of blocks stored
   * @return - the block report - the full list of blocks stored
   */
  public Block[] getBlockReport();

  /**
   * Is the block valid?
   * @param b
   * @return - true if the specified block is valid
   */
  public boolean isValidBlock(Block b);

  /**
   * Invalidates the specified blocks
   * @param invalidBlks - the blocks to be invalidated
   * @throws IOException
   */
  public void invalidate(Block invalidBlks[]) throws IOException;

    /**
     * Check if all the data directories are healthy
     * @throws DiskErrorException
     */
  public void checkDataDir() throws DiskErrorException;
      
    /**
     * Stringifies the name of the storage
     */
  public String toString();

}
