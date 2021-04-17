package disk_store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * An ordered index.  Duplicate search key values are allowed,
 * but not duplicate index table entries.  In DB terminology, a
 * search key is not a superkey.
 *
 * A limitation of this class is that only single integer search
 * keys are supported.
 *
 *  * Referenced Binary Search -> https://www.geeksforgeeks.org/binary-search/ *
 */

public class OrdIndex implements DBIndex {

	/**
	 * Create nested list of index entries
	 */
	private final List <List <Integer>> entries;

	/**
	 * Create an new ordered index called entries
	 */
	public OrdIndex() {

		entries = new ArrayList <>();
	}

	/**
	 * Binary search that searches nested list for key
	 * @param arr - nested list
	 * @param key - index
	 * @return found key return index, if not found return -1
	 */
	public int binarySearch(List <List <Integer>> arr, int key) {
		int lo = 0;
		int hi = arr.size()-1;

		while (lo <= hi) {
			int mid = lo + (hi - lo)/ 2;
			// Check if key is present at mid
			if (arr.get(mid).get(0) == key) {
				return mid;
			}
			// If key is greater, ignore left half
			if (arr.get(mid).get(0) < key) {
				lo = mid + 1;
			}
			// If key is smaller, ignore right half
			else
				hi = mid - 1;
		}
		// Key was not present
		return -1;
	}

	/**
	 * Binary search that returns position to insert new key
	 * @param arr - nested list
	 * @param key - index
	 * @return position to add index
	 */
	public int insertSearch(List <List <Integer>> arr, int key) {
		int lo = 0;
		int hi = arr.size()-1;

		if (key > arr.get(hi).get(0)) return hi+1;
		if (key == arr.get(hi).get(0)) return hi;

		while (hi-lo > 1) {
			int mid = (lo+hi) / 2;
			// Check if key is present at mid
			if (key == arr.get(mid).get(0)){
				return mid;
			}

			// If key is greater, ignore left half
			if (key < arr.get(mid).get(0)) {
				hi = mid;

			} else {
				// If key is smaller, ignore right half
				lo = mid;
			}
		}
		return hi;
	}

	/**
	 * Binary search to find index of block number
	 * @param arr - block number array
	 * @param key - block number
	 * @return index of block number
	 */
	public int deleteSearch(List <Integer> arr, int key) {
		int lo = 0;
		int hi = arr.size()-1;

		while (lo <= hi) {
			int mid = lo + (hi - lo)/ 2;
			// Check if key is present at mid
			if (arr.get(mid) == key) {
				return mid;
			}
			// If key is greater, ignore left half
			if (arr.get(mid) < key) {
				lo = mid + 1;
			}
			// If key is smaller, ignore right half
			else {
				hi = mid - 1;
			}
		}
		// Key was not present
		return -1;
	}

	/**
	 * Return a list of all the block numbers values associated with the
	 * given search key in the index (return an empty list if the
	 * key does not appear in the index)
	 * @param key value of a search key
	 * @return a list of block numbers
	 */
	@Override
	public List<Integer> lookup(int key) {

		// Binary search of entries in list
		int index = binarySearch(entries, key);
		// Create empty list
		List <Integer> blocks = new ArrayList <>();
		// If no key found, return an empty list
		if (index == -1) {
			return blocks;
		}
		// List of block numbers with same key from entries list added to blocks list
		blocks = entries.get(index).subList(1, entries.get(index).size());
		// Creates HashSet to hold unique values
		Set <Integer> blockSet = new HashSet <>();
		// Add unique values from blocks to blockSet
		blockSet.addAll(blocks);
		// Create new list
		List <Integer> blocks2 = new ArrayList <>();
		// Add unique values from Hashset to blocks 2 list, so we return a list
		blocks2.addAll(blockSet);
		// return list of block numbers (no duplicates)
		return blocks2;
	}

	/**
	 * Insert the key/blockNum pair into the index.  If the pair is
	 * already present, it is not inserted.
	 * @param key - search key
	 * @param blockNum - block number
	 */
	@Override
	public void insert(int key, int blockNum) {
		// If entries is empty
		if(entries.size() > 0) {
			// Use binary search to find position for insert
			int index = binarySearch(entries, key);

			if (index == -1) {
				index = insertSearch(entries, key);
			}
			// Does key go at end of entries?
			if (index > entries.size()-1) {
				// Create new list and add to entries at discovered location
				ArrayList <Integer> blocks = new ArrayList <>();
				entries.add(blocks);
				// Add key and block number to list at index at discovered location
				entries.get(index).add(key);
				entries.get(index).add(blockNum);
			}
			else {
				// Does key go at a position that's taken by another key value?
				if (entries.get(index).get(0) != key) {
					// Create new list and add to entries to index
					ArrayList <Integer> list = new ArrayList <>();
					entries.add(index, list);
					// Add key and block number to entries by index
					entries.get(index).add(key);
					entries.get(index).add(blockNum);
				}
				// If key already exists in entries
				else {
					// Add block number to already existing list
					entries.get(index).add(blockNum);
					int size = entries.get(index).size();
					// Sort values in entries, skip key value
					Collections.sort(entries.get(index).subList(1, size));
				}
			}
		}
		else {
			// Create new array and add to entries
			ArrayList<Integer> list = new ArrayList<>();
			entries.add(0, list);
			// Insert key and block number to the list in entries
			entries.get(0).add(key);
			entries.get(0).add(blockNum);
		}
	}

	/**
	 *
	 * @param key value of a search key
	 * @param blockNum a DB block number
	 */
	@Override
	public void delete(int key, int blockNum) {
		// Create new list to hold block numbers with same key
		List<Integer> blocks;
		int index, index2;
		// Binary search entries list to check is key exists
		index = binarySearch(entries, key);

		// Check is key exists or not
		if (index == -1) {
			// does not exist, do nothing
		} else {
			// If key is found
			// Add block numbers that are paired with search key to new list
			blocks =  entries.get(index).subList(1, entries.get(index).size());
			// Binary search to find index of block number
			index2 = deleteSearch(blocks, blockNum);
			// If block number exists, delete
			if (index2 != -1) {
				entries.get(index).remove(index2+1);
			}

			if (entries.get(index).size() == 1) {
				entries.remove(index);
			}
		}
	}

	/**
	 * Return the number of entries in the index
	 * @return count size of each sub-array
	 */
	public int size() {
		int size = 0;
		for (int i = 0; i < entries.size(); i++) {
			size += entries.get(i).size()-1;
		}
		return size;
	}

	@Override
	public String toString() {
		return entries.toString();
	}
}