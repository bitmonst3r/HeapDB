package disk_store;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A heap file implementation of the DB interface. Record layout within blocks
 * uses a bit map.
 *
 * @author Glenn
 *
 * modified. 3/14/2020 David
 * Changed block 1 bitmap.
 * Bit=0 means block has empty row space.
 * Bit=1 means block is full.
 *
 */

public class HeapDB implements DB, Iterable<Record> {

	private BlockedFile bf;
	private Schema schema;

	// metadata block
	private static final int metadataBlock = 0; // index of block containing metadata
	private static final int dbType = 0;
	private static final int dbVersion = 1;
	private static final int fileTypePosition = 0;
	private static final int versionPosition = fileTypePosition + Integer.BYTES;
	private static final int schemaPosition = versionPosition + Integer.BYTES;

	// bitmap block
	private static final int bitmapBlock = 1; // index of block containing block bitmap
	private Bitmap blockMap; // the bitmap object
	private BlockBuffer blockmapBuffer; // the buffer used for reading the block bitmap

	// buffer for reading/writing records, and its corresponding record bitmap
	private BlockBuffer buffer;
	private Bitmap recMap;

	// block layout (see details above)
	private int recSize; // number of bytes per record
	private int recMapSize; // number of bytes in record bitmap
	private int recsPerBlock; // number of records per block

	// indexes[fieldNum] is the index for the field of the schema with
	// with the given number
	DBIndex[] indexes;

	// private constructor
	private HeapDB(BlockedFile bf, Schema schema) {
		this.bf = bf;
		this.schema = schema;
		setRecordLayout();
		indexes = new DBIndex[schema.size()];
	}

	/**
	 * Create a new, empty database with the given schema.
	 *
	 * @param filename
	 * @param schema
	 */
	public HeapDB(String filename, Schema schema) {
		bf = new BlockedFile(filename);
		this.schema = schema;

		// block 0: metadata block
		BlockBuffer metaBuffer = bf.getBuffer();
		metaBuffer.putInt(fileTypePosition, dbType);
		metaBuffer.putInt(versionPosition, dbVersion);
		int temp = metaBuffer.getInt(fileTypePosition);
		schema.serialize(metaBuffer.buffer, schemaPosition);
		temp = metaBuffer.getInt(versionPosition);
		bf.write(metadataBlock, metaBuffer);

		// block 1: bitmap block
		blockmapBuffer = bf.getBuffer();
		blockMap = new Bitmap(blockmapBuffer.buffer.array());
		blockMap.clear();
		blockMap.setBit(bitmapBlock, true); // dw
		blockMap.setBit(metadataBlock, true); // dw
		bf.write(bitmapBlock, blockmapBuffer);

		setRecordLayout();

		// create a buffer for reading/writing records;
		buffer = bf.getBuffer();
		recMap = new Bitmap(buffer.buffer.array(), recMapSize);

		// initialize the DB index array
		indexes = new DBIndex[schema.size()];
	}

	/**
	 * Open an existing heap database. The schema is read from the database file.
	 *
	 * @param filename
	 * @return
	 */
	public static HeapDB open(String filename) {
		// open the file and read the schema
		BlockedFile bf = BlockedFile.open(filename);

		// read the metadata block to get the schema
		BlockBuffer metaBuffer = bf.getBuffer();
		bf.read(metadataBlock, metaBuffer);
		int temp = metaBuffer.get();
		int fileType = metaBuffer.getInt(fileTypePosition);
		int version = metaBuffer.getInt(versionPosition);
		Schema schema = Schema.deserialize(metaBuffer.buffer, schemaPosition);

		// create the database
		HeapDB db = new HeapDB(bf, schema);

		// create the bitmap buffer and record buffers
		db.blockmapBuffer = bf.getBuffer();
		db.blockMap = new Bitmap(db.blockmapBuffer.buffer.array());
		db.buffer = bf.getBuffer();
		db.recMap = new Bitmap(db.buffer.buffer.array(), db.recMapSize);

		return db;
	}

	/**
	 * Close the database.
	 */
	public void close() {
		bf.close();
	}

	// compute the layout of records in blocks
	private void setRecordLayout() {
		// Each block that is used to store records will contain:
		// - a bit map, with one bit for each record
		// - the records themselves
		// For every record that is stored, we need enough bytes for the
		// record plus a bit for that record in the bit map. So instead
		// of dividing the block size by the number of bytes per record (call this b),
		// we divide by b + 1/8.
		recSize = schema.getLen();
		double b = (double) recSize; // bytes/rec
		double s = (double) bf.blockSize(); // bytes/block
		recsPerBlock = (int) Math.floor(((s - 1) * Byte.SIZE) / (Byte.SIZE * b + 1));
		recMapSize = (int) Math.floor((double) recsPerBlock / Byte.SIZE);
	}

	// return the byte position within a block where the ith record is stored
	private int recordLocation(int recNumber) {
		return recMapSize + recSize * recNumber;
	}

	/**
	 * Return the number of records in the database. Note: this does a linear
	 * search, so is slow. It would be better to keep an instance variable that
	 * tracks the current size.
	 *
	 * @return
	 */
	public int size() {
		int cnt = 0;
		for (Record rec : this) {
			cnt++;
		}
		return cnt;
	}

	@Override
	public boolean insert(Record rec) {
		// make sure no record with rec's key is already in the database
		if (lookup(rec.getKey()) != null) {
			return false;
		}

		// iterate over valid blocks and see if there is space
		bf.read(bitmapBlock, blockmapBuffer); // read the bitmap block
		int blockNum = blockMap.firstZero(); // get first block with space.
		// check that blockNum is a valid block
		if (blockNum > 1 && blockNum <= bf.getLastBlockIndex()) {
			// block i is valid, so see if it has room for a new record
			bf.read(blockNum, buffer);
			int recNum = recMap.firstZero();
			if (recNum >= 0) {
				// write record to buffer, set bit in bit map, write to file
				int loc = recordLocation(recNum);
				rec.serialize(buffer.buffer, loc);
				recMap.setBit(recNum, true);
				bf.write(blockNum, buffer);
				// if block is now full, update blockMap to no space and save blockMap to disk.
				if (recMap.firstZero() < 0) {
					blockMap.setBit(blockNum, true);
					bf.write(bitmapBlock, blockmapBuffer);
				}

				for (int i=0; i< indexes.length; i++) {
					if (indexes[i]!=null) {
						//index maintenance, getting a record value to insert into indexes
						IntField recordfield = (IntField)rec.get(i);
						indexes[i].insert( recordfield.getValue(), blockNum);
					}
				}
				return true;
			}
		}

		// come here when no space in valid blocks, so start a new block
		if (blockNum < 0) {
			// no room left in the database
			throw new IllegalStateException("Error: insert failed because database is full");
		}
		int newBlockNum = (int) bf.getLastBlockIndex() + 1;
		// initialize a new block and retry the insert
		recMap.clear();
		bf.write(newBlockNum, buffer);
		blockMap.setBit(newBlockNum, false);
		bf.write(bitmapBlock, blockmapBuffer);
		return insert(rec);
	}

	@Override
	public boolean delete(int key) {
		Record rec = schema.blankRecord();

		// search blocks sequentially for the key
		bf.read(bitmapBlock, blockmapBuffer); // read the bitmap block
		for (int blockNum = bitmapBlock + 1; blockNum <= bf.getLastBlockIndex(); blockNum++) {

			bf.read(blockNum, buffer);
			for (int recNum = 0; recNum < recMap.size(); recNum++) {
				if (recMap.getBit(recNum)) {
					// record j is present; check its key value
					int loc = recordLocation(recNum);
					rec.deserialize(buffer.buffer, loc);
					if (key == rec.getKey()) {
						// found it; to delete the record, simply zero the jth
						// bit in the record bit map
						recMap.setBit(recNum, false);
						bf.write(blockNum, buffer);
						if (blockMap.getBit(blockNum) == true) {
							// update blockMap, there is space available in this block now.
							blockMap.setBit(blockNum, false);
							bf.write(bitmapBlock, blockmapBuffer);
						}

						for (int i=0; i< indexes.length; i++) {
							if (indexes[i]!=null) {
								// index maintenance
								//same as insert but instead uses delete.
								IntField recordfield= (IntField)rec.get(i);
								indexes[i].delete(recordfield.getValue(), blockNum );
							}
						}
						return true;
					}
				}
			}
		}
		return false;
	}

	@Override
	public boolean modify(Record rec) {
		throw new UnsupportedOperationException();
	}

	// Return the record in the database having the
	// given primary key value, or return null if no
	// such record.
	public Record lookup(int key) {
		List<Record> recs = lookup(schema.getKey(), key);
		if (recs.size() == 0) {
			return null;
		}
		return recs.get(0);
	}

	@Override
	public List<Record> lookup(String fname, int key) {
		int fieldNum = schema.getFieldIndex(fname);
		if (fieldNum < 0) {
			throw new IllegalArgumentException("Field '" + fname + "' not in schema.");
		}

		List<Record> result = new ArrayList<>();

		if (indexes[fieldNum]==null) {
			// no index on this column.  do linear scan
			// add all records into "result"
			//where the column value key is fieldnum
			for (Record rec : this) {
				IntField recordfield = (IntField)rec.get(fieldNum);
				// have to check if the record value is equal to the key
				// if so added to the list
				if(recordfield.getValue()==key){
					result.add(rec);
				}
			}
		} else {
			// Do an index lookup of the key
			// For each block in the blocknumbers of the index lookup
			List<Integer> listofblocks =indexes[fieldNum].lookup(key);
			for(Integer blocks : listofblocks ){
				// get the records of the block
				// for each record in the records
				// Add that record to the results
				List<Record> reclist =lookupInBlock(fieldNum, key, blocks);
				for(Record rec:reclist){
					result.add(rec);
				}
			}
		}
		return result;
	}

	// Perform a linear search in the block with the given blockNum
	// for records in which the given integer field has value key
	public List<Record> lookupInBlock(int fieldNum, int key, int blockNum) {
		List<Record> result = new ArrayList<Record>();

		// each record in the block will be deserialized into this record
		Record rec = schema.blankRecord();

		bf.read(blockNum, buffer);
		for (int recNum = 0; recNum < recMap.size(); recNum++) {
			if (recMap.getBit(recNum)) {
				// found a record
				int loc = recordLocation(recNum);
				rec.deserialize(buffer.buffer, loc);
				int fieldVal = ((IntField) rec.get(fieldNum)).getValue();
				if (fieldVal == key) {
					// key found. return a copy of record.
					Record newRecord = schema.blankRecord();
					newRecord.deserialize(buffer.buffer, loc);
					result.add(newRecord);
				}
			}
		}
		return result;
	}

	/**
	 * Create an ordered index for the given integer field.
	 */
	public void createOrderedIndex(String fname) {
		int fieldNum = schema.getFieldIndex(fname);
		if (fieldNum < 0) {
			throw new IllegalArgumentException("no such field: " + fname);
		}
		FieldType ft = schema.getType(fieldNum);
		if (!(ft instanceof IntType)) {
			throw new IllegalArgumentException("field " + fname + " is not of integer type");
		}

		DBIndex index = new OrdIndex();
		initializeIndex(fieldNum, index);
		indexes[fieldNum] = index;
	}

	/**
	 * Create an ordered index for the key field.
	 */
	public void createOrderedIndex() {
		createOrderedIndex(schema.getKey());
	}

	/**
	 * Create a hash index for the given integer field.
	 */
	public void createHashIndex(String fname) {
		int fieldNum = schema.getFieldIndex(fname);
		if (fieldNum < 0) {
			throw new IllegalArgumentException("no such field: " + fname);
		}
		FieldType ft = schema.getType(fieldNum);
		if (!(ft instanceof IntType)) {
			throw new IllegalArgumentException("field " + fname + " is not of integer type");
		}

		DBIndex index = new HashIndex();
		initializeIndex(fieldNum, index);
		indexes[fieldNum] = index;
	}

	/**
	 * Create a hash index for the primary key field.
	 */
	public void createHashIndex() {
		createHashIndex(schema.getKey());
	}

	// initialize the given index
	private void initializeIndex(int fieldNum, DBIndex index) {
		if (index == null) {
			throw new IllegalArgumentException("index is null");
		}

		// for each record in the DB, you will need to insert its
		// index column value and the block number
		Record rec = schema.blankRecord();
		// read  block bitmap
		bf.read(bitmapBlock, blockmapBuffer);

		// iterate through the blocks
		for (int blockNum = bitmapBlock + 1; blockNum <= bf.getLastBlockIndex(); blockNum++) {

			//iterating through the records
			for (int recNum = 0; recNum < recMap.size(); recNum++) {
				if (recMap.getBit(recNum)) {
					//find record location and use to deserialize
					int loc = recordLocation(recNum);
					rec.deserialize(buffer.buffer, loc);
					//inserting values into the index.
					IntField recordfield = (IntField)rec.get(fieldNum);
					index.insert(recordfield.getValue(), blockNum);
				}
			}
		}
	}

	/**
	 * Delete the index for the given field. Do nothing if no index exists for the
	 * given field.
	 */
	public void deleteIndex(String fname) {
		int fieldNum = schema.getFieldIndex(fname);
		if (fieldNum < 0) {
			throw new IllegalArgumentException("no such field: " + fname);
		}
		indexes[fieldNum] = null;
	}

	/**
	 * Delete the index for the primary key.
	 */
	public void deleteIndex() {
		deleteIndex(schema.getKey());
	}

	/**
	 * Iterate over all the records in this DB.
	 */
	public Iterator<Record> iterator() {
		return new DBIterator();
	}

	// An Iterator over the records in the database, implemented as a nested class.
	private class DBIterator implements Iterator<Record> {
		Record rec;
		int b, nb; // block number, number of blocks
		int r, nr; // record number, number of records

		DBIterator() {
			rec = schema.blankRecord();
			bf.read(bitmapBlock, blockmapBuffer);
			b = bitmapBlock + 1; // first data block
			nb = (int) bf.getLastBlockIndex(); // FIX THIS
			r = -1; // a value of -1 means block status is unknown
			nr = recMap.size();
			findNext();
		}

		// locate next (b,r) value such that the bit is set
		// on the block map at b and on the record map at r
		private void findNext() {
			// if r is -1, status of block b is unknown
			if (r == -1) {

				if (b > bf.getLastBlockIndex()) {
					// no more blocks available
					r = nr;
					return;
				}
				// block b is in use; read it
				bf.read(b, buffer);
			}
			// find a record r in block b
			r++;
			while (r < nr) {
				if (recMap.getBit(r)) {
					break;
				}
				r++;
			}
			if (r == nr) {
				// no record slots available
				b++;
				r = -1;
				findNext();
			}
		}

		public boolean hasNext() {
			return b <= nb || r < nr;
		}

		public Record next() {
			// block b is currently in the buffer
			int index = recordLocation(r);
			rec.deserialize(buffer.buffer, index);
			findNext();
			return rec;
		}
	}

	/**
	 * An alternative to toString() that is useful for debugging.
	 *
	 * @return
	 */
	public String toStringDiagnostic() {
		StringBuffer sb = new StringBuffer();
		Record rec = schema.blankRecord();

		// read and print the block bitmap
		bf.read(bitmapBlock, blockmapBuffer);
		sb.append("Block bitmap:  " + blockMap);

		for (int blockNum = bitmapBlock + 1; blockNum <= bf.getLastBlockIndex(); blockNum++) {
			bf.read(blockNum, buffer);
			// print the record bitmap of block
			sb.append("Block " + blockNum + "\n");
			sb.append("Record bitmap: " + recMap + "\n");
			int recsOnLine = 0;
			for (int recNum = 0; recNum < recMap.size(); recNum++) {
				if (recMap.getBit(recNum)) {
					// record j is present; check its key value
					int loc = recordLocation(recNum);
					rec.deserialize(buffer.buffer, loc);
					sb.append(rec); //dont need
					recsOnLine++;
					if (recsOnLine % 16 == 0) {
						sb.append("\n");
					}
				}
			}
			sb.append("\n");
		}
		return sb.toString();
	}
	@Override
	public String toString() {
		StringBuffer sb = new StringBuffer();
		for (Record rec : this) {
			sb.append(rec + "\n");
		}
		return sb.toString();
	}
}
