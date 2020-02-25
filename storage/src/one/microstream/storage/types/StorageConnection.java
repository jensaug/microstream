package one.microstream.storage.types;

import static one.microstream.X.notNull;

import java.nio.file.Path;
import java.util.function.Predicate;

import one.microstream.collections.types.XGettingEnum;
import one.microstream.persistence.binary.types.Binary;
import one.microstream.persistence.types.PersistenceManager;
import one.microstream.persistence.types.Persister;
import one.microstream.persistence.types.Storer;
import one.microstream.persistence.types.Unpersistable;


/**
 * Ultra-thin delegatig type that connects a {@link PersistenceManager} instance (potentially exclusively created)
 * to a storage instance.
 * <p>
 * Note that this is a rather "internal" type that users usually do not have to use or care about.
 * Since {@link StorageManager} implements this interface, is is normally sufficient to use just that.
 *
 * @author TM
 */
public interface StorageConnection extends Persister
{
	/* (11.05.2014 TM)TODO: Proper InterruptedException handling
	 *  just returning, especially returning null (see below) seems quite dangerous.
	 *  Research how to handle such cases properly.
	 *  Difficult: what to return if the thread has been aborted? Throw an exception?
	 *  Maybe set the thread's interrupted flag (seen once in an article)
	 */

	// (03.12.2014 TM)TODO: method to query the transactions files content because channels have a lock on it

	// currently only for type parameter fixation

	/**
	 * Issues a full garbage collection to be executed. Depending on the size of the database,
	 * the available cache, used hardware, etc., this can take any amount of time.
	 * <p>
	 * Garbage collection marks all persisted objects/records that are reachable from the root (mark phase)
	 * and once that is completed, all non-marked records are determined to be effectively unreachable
	 * and are thus deleted. This common mechanism in graph-organised data completely removes the need
	 * for any explicit deleting.
	 * <p>
	 * Note that the garbage collection on the storage level has nothing to do with the JVM's Garbage Collector
	 * on the heap level. While the technical principle is the same, both GCs are separate from each other and
	 * do not have anything to do with each other.
	 * 
	 * @see #issueGarbageCollection(long)
	 */
	public default void issueFullGarbageCollection()
	{
		this.issueGarbageCollection(Long.MAX_VALUE);
	}

	/**
	 * Issues garbage collection to be executed, limited to the time budget in nanoseconds specified
	 * by the passed {@code nanoTimeBudget}.<br>
	 * When the time budget is used up, the garbage collector will keep the current progress and continue there
	 * at the next opportunity. The same progress marker is used by the implicit housekeeping, so both mechanisms
	 * will continue on the same progress.<br>
	 * If no store has occured since the last completed garbage sweep, this method will have no effect and return
	 * immediately.
	 * 
	 * @param nanoTimeBudget the time budget in nanoseconds to be used to perform garbage collection.
	 * 
	 * @return whether the returned call has completed garbage collection.
	 * 
	 * @see #issueFullGarbageCollection()
	 */
	public boolean issueGarbageCollection(long nanoTimeBudget);

	/**
	 * Issues a full storage file check to be executed. Depending on the size of the database,
	 * the available cache, used hardware, etc., this can take any amount of time.
	 * <p>
	 * File checking evaluates every storage data file about being either too small, too big
	 * or having too many logical "gaps" in it (created by storing newer versions of an object
	 * or by garbage collection). If one of those checks applies, the remaining live data in
	 * the file is moved to the current head file and once that is done, the source file
	 * (now consisting of 100% logical "gaps", making it effectively superfluous) is then deleted.
	 * <p>
	 * The exact logic is defined by {@link StorageConfiguration#dataFileEvaluator()}
	 * 
	 * @see #issueFileCheck(long)
	 */
	public default void issueFullFileCheck()
	{
		this.issueFileCheck(Long.MAX_VALUE);
	}

	/**
	 * Issues a storage file check to be executed, limited to the time budget in nanoseconds specified
	 * by the passed {@code nanoTimeBudget}.<br>
	 * When the time budget is used up, the checking logic will keep the current progress and continue there
	 * at the next opportunity. The same progress marker is used by the implicit housekeeping, so both mechanisms
	 * will continue on the same progress.<br>
	 * If no store has occured since the last completed check, this method will have no effect and return
	 * immediately.
	 * 
	 * @param nanoTimeBudget the time budget in nanoseconds to be used to perform file checking.
	 * 
	 * @return whether the returned call has completed file checking.
	 */
	public boolean issueFileCheck(long nanoTimeBudget);
	
	/* (06.02.2020 TM)NOTE: As shown by HG, allowing one-time custom evaluators can cause conflicts.
	 * E.g. infinite loops:
	 * - Default evaluator allows 8 MB files
	 * - Custom evaluator allows only 4 MB files
	 * - So the call splits an 8 MB file
	 * - The new file is filled up to 8 MB based on the default evaluator
	 * - Then it is evaluated by the custom evaluator and split again
	 * - This repeats forever
	 * 
	 * On a more general note:
	 * In contrary to cache management, it hardly makes sense to interrupt the default logic,
	 * mess around with all the storage files once and then fall back to the default logic,
	 * undoing all changes according to its own strategy.
	 * 
	 * In any case, this method hardly makes sense.
	 */
//	public default void issueFullFileCheck(final StorageDataFileDissolvingEvaluator fileDissolvingEvaluator)
//	{
//		 this.issueFileCheck(Long.MAX_VALUE, fileDissolvingEvaluator);
//	}

//	public boolean issueFileCheck(long nanoTimeBudget, StorageDataFileDissolvingEvaluator fileDissolvingEvaluator);

	public default void issueFullCacheCheck()
	{
		this.issueFullCacheCheck(null); // providing no explicit evaluator means to use the internal one
	}

	public default void issueFullCacheCheck(final StorageEntityCacheEvaluator entityEvaluator)
	{
		this.issueCacheCheck(Long.MAX_VALUE, entityEvaluator);
	}

	public default boolean issueCacheCheck(final long nanoTimeBudget)
	{
		return this.issueCacheCheck(nanoTimeBudget, null);
	}

	public boolean issueCacheCheck(long nanoTimeBudget, StorageEntityCacheEvaluator entityEvaluator);

	public StorageRawFileStatistics createStorageStatistics();

	/* (28.06.2013 TM)TODO: post-sweep-task queue?
	 * even more practical then or additional to the above would be to have a post-sweep task queue
	 * that gets executed automatically after a sweep is completed.
	 * That way, things like backups could be set up to occur automatically at the right time
	 * without having to actively poll (trial-and-error) for it.
	 * Should not be to complicated as the phase check already is a task
	 */
	public void exportChannels(StorageIoHandler fileHandler, boolean performGarbageCollection);

	public default void exportChannels(final StorageIoHandler fileHandler)
	{
		this.exportChannels(fileHandler, true);
	}

	public default StorageEntityTypeExportStatistics exportTypes(
		final StorageEntityTypeExportFileProvider exportFileProvider
	)
	{
		return this.exportTypes(exportFileProvider, null);
	}
	
	public StorageEntityTypeExportStatistics exportTypes(
		StorageEntityTypeExportFileProvider         exportFileProvider,
		Predicate<? super StorageEntityTypeHandler> isExportType
	);
	

	public void importFiles(XGettingEnum<Path> importFiles);

	/* (13.07.2015 TM)TODO: load by type somehow
	 * Query by typeId already implemented. Question is how to best provide it to the user.
	 * As a result HashTable or Sequence?
	 * By class or by type id or both?
	 */

//	public XGettingTable<Class<?>, ? extends XGettingEnum<?>> loadAllByTypes(XGettingEnum<Class<?>> types);


	public PersistenceManager<Binary> persistenceManager();

	@Override
	public default long store(final Object instance)
	{
		return this.persistenceManager().store(instance);
	}
	
	@Override
	public default long[] storeAll(final Object... instances)
	{
		return this.persistenceManager().storeAll(instances);
	}
	
	@Override
	public default void storeAll(final Iterable<?> instances)
	{
		this.persistenceManager().storeAll(instances);
	}
	
	@Override
	public default Storer createLazyStorer()
	{
		return this.persistenceManager().createLazyStorer();
	}

	@Override
	public default Storer createStorer()
	{
		return this.persistenceManager().createStorer();
	}
	
	@Override
	public default Storer createEagerStorer()
	{
		return this.persistenceManager().createEagerStorer();
	}
	
	@Override
	public default Object getObject(final long objectId)
	{
		return this.persistenceManager().getObject(objectId);
	}



	public final class Default implements StorageConnection, Unpersistable
	{
		///////////////////////////////////////////////////////////////////////////
		// instance fields //
		////////////////////

		/* The performance penalty of this indirection is negligible as a persistence manager instance
		 * is only (properly) used for non-performance-relevant uses and otherwise spawns dedicated
		 * storer/loader instances.
		 */
		private final PersistenceManager<Binary> delegate                 ;
		private final StorageRequestAcceptor     connectionRequestAcceptor;



		///////////////////////////////////////////////////////////////////////////
		// constructors //
		/////////////////

		public Default(
			final PersistenceManager<Binary> delegate                 ,
			final StorageRequestAcceptor     connectionRequestAcceptor
		)
		{
			super();
			this.delegate                  = notNull(delegate)                 ;
			this.connectionRequestAcceptor = notNull(connectionRequestAcceptor);
		}



		///////////////////////////////////////////////////////////////////////////
		// methods //
		////////////

//		@Override
//		public final void cleanUp()
//		{
//			this.delegate.cleanUp();
//		}
//
//		@Override
//		public final Object lookupObject(final long objectId)
//		{
//			return this.delegate.lookupObject(objectId);
//		}
//
//		@Override
//		public final long lookupObjectId(final Object object)
//		{
//			return this.delegate.lookupObjectId(object);
//		}
//
//		@Override
//		public final long ensureObjectId(final Object object)
//		{
//			return this.delegate.ensureObjectId(object);
//		}
//
//		@Override
//		public long currentObjectId()
//		{
//			return this.delegate.currentObjectId();
//		}
//
//		@Override
//		public final PersistenceLoader<Binary> createLoader()
//		{
//			return this.delegate.createLoader();
//		}
//
//		@Override
//		public final PersistenceStorer<Binary> createStorer()
//		{
//			return this.delegate.createStorer();
//		}
//
//		@Override
//		public final PersistenceStorer<Binary> createStorer(final BufferSizeProvider bufferSizeProvider)
//		{
//			return this.delegate.createStorer(bufferSizeProvider);
//		}
//
//		@Override
//		public final PersistenceRegisterer createRegisterer()
//		{
//			return this.delegate.createRegisterer();
//		}
//
//		@Override
//		public final Object initialGet()
//		{
//			return this.delegate.initialGet();
//		}
//
//		@Override
//		public final Object get(final long objectId)
//		{
//			return this.delegate.get(objectId);
//		}
//
//		@Override
//		public final <C extends Procedure<Object>> C collect(final C collector, final long... oids)
//		{
//			return this.delegate.collect(collector, oids);
//		}
//
//		@Override
//		public final long storeFull(final Object instance)
//		{
//			return this.delegate.storeFull(instance);
//		}
//
//		@Override
//		public long storeRequired(final Object instance)
//		{
//			return this.delegate.store(instance);
//		}
//
//		@Override
//		public final long[] storeAllFull(final Object... instances)
//		{
//			return this.delegate.storeAllFull(instances);
//		}
//
//		@Override
//		public long[] storeAllRequired(final Object... instances)
//		{
//			return this.delegate.storeAll(instances);
//		}
//
//		@Override
//		public final PersistenceSource<Binary> source()
//		{
//			return this.delegate.source();
//		}
//
//		@Override
//		public void updateMetadata(
//			final PersistenceTypeDictionary typeDictionary ,
//			final long                      highestTypeId  ,
//			final long                      highestObjectId
//		)
//		{
//			this.delegate.updateMetadata(typeDictionary, highestTypeId, highestObjectId);
//		}
//
//		@Override
//		public void updateCurrentObjectId(final long currentObjectId)
//		{
//			this.delegate.updateCurrentObjectId(currentObjectId);
//		}

		@Override
		public PersistenceManager<Binary> persistenceManager()
		{
			return this.delegate;
		}

		@Override
		public final boolean issueGarbageCollection(final long nanoTimeBudget)
		{
			try
			{
				// a time budget <= 0 will effectively be a cheap query for the completion state.
				return this.connectionRequestAcceptor.issueGarbageCollection(nanoTimeBudget);
			}
			catch(final InterruptedException e)
			{
				// thread interrupted, task aborted, return
				return false;
			}
		}

		@Override
		public final boolean issueFileCheck(final long nanoTimeBudget)
		{
			try
			{
				// a time budget <= 0 will effectively be a cheap query for the completion state.
				return this.connectionRequestAcceptor.issueFileCheck(nanoTimeBudget);
			}
			catch(final InterruptedException e)
			{
				// thread interrupted, task aborted, return
				return false;
			}
		}

		@Override
		public final boolean issueCacheCheck(
			final long                        nanoTimeBudget,
			final StorageEntityCacheEvaluator entityEvaluator
		)
		{
			try
			{
				// a time budget <= 0 will effectively be a cheap query for the completion state.
				return this.connectionRequestAcceptor.issueCacheCheck(nanoTimeBudget, entityEvaluator);
			}
			catch(final InterruptedException e)
			{
				// thread interrupted, task aborted, return
				return false;
			}
		}

		@Override
		public StorageRawFileStatistics createStorageStatistics()
		{
			try
			{
				return this.connectionRequestAcceptor.createStatistics();
			}
			catch(final InterruptedException e)
			{
				// thread interrupted, task aborted, return
				return null;
			}
		}

		@Override
		public void exportChannels(final StorageIoHandler fileHandler, final boolean performGarbageCollection)
		{
			try
			{
				this.connectionRequestAcceptor.exportChannels(fileHandler, performGarbageCollection);
			}
			catch(final InterruptedException e)
			{
				// thread interrupted, task aborted, return
				return;
			}
		}

		@Override
		public StorageEntityTypeExportStatistics exportTypes(
			final StorageEntityTypeExportFileProvider         exportFileProvider,
			final Predicate<? super StorageEntityTypeHandler> isExportType
		)
		{
			try
			{
				return this.connectionRequestAcceptor.exportTypes(exportFileProvider, isExportType);
			}
			catch(final InterruptedException e)
			{
				// thread interrupted, task aborted, return
				return null;
			}
		}

		@Override
		public void importFiles(final XGettingEnum<Path> importFiles)
		{
			try
			{
				this.connectionRequestAcceptor.importFiles(importFiles);
			}
			catch(final InterruptedException e)
			{
				// thread interrupted, task aborted, return
				return;
			}
		}

	}

}
