/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.nioneo.store;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.helpers.Settings;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.DefaultIdGeneratorFactory;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.nioneo.xa.NeoStoreProvider;
import org.neo4j.kernel.impl.util.StringLogger;
import org.neo4j.kernel.monitoring.Monitors;

/**
 * Not thread safe (since DiffRecordStore is not thread safe), intended for
 * single threaded use.
 */
public class StoreAccess
{
    // Top level stores
    private final RecordStore<DynamicRecord> schemaStore;
    private final RecordStore<NodeRecord> nodeStore;
    private final RecordStore<RelationshipRecord> relStore;
    private final RecordStore<RelationshipTypeTokenRecord> relationshipTypeTokenStore;
    private final RecordStore<LabelTokenRecord> labelTokenStore;
    private final RecordStore<DynamicRecord> nodeDynamicLabelStore;
    private final RecordStore<PropertyRecord> propStore;
    // Transitive stores
    private final RecordStore<DynamicRecord> stringStore, arrayStore;
    private final RecordStore<PropertyKeyTokenRecord> propertyKeyTokenStore;
    private final RecordStore<DynamicRecord> relationshipTypeNameStore;
    private final RecordStore<DynamicRecord> labelNameStore;
    private final RecordStore<DynamicRecord> propertyKeyNameStore;
    private final RecordStore<RelationshipGroupRecord> relGroupStore;
    // internal state
    private boolean closeable;
    private NeoStore neoStore;

    public StoreAccess( GraphDatabaseAPI graphdb )
    {
        this( getNeoStoreFrom( graphdb ) );
    }

    @SuppressWarnings( "deprecation" )
    private static NeoStore getNeoStoreFrom( GraphDatabaseAPI graphdb )
    {
        return graphdb.getDependencyResolver().resolveDependency( NeoStoreProvider.class ).evaluate();
    }

    public StoreAccess( NeoStore store )
    {
        this( store.getSchemaStore(), store.getNodeStore(), store.getRelationshipStore(), store.getPropertyStore(),
                store.getRelationshipTypeTokenStore(), store.getLabelTokenStore(), store.getRelationshipGroupStore() );
        this.neoStore = store;
    }

    public StoreAccess( SchemaStore schemaStore, NodeStore nodeStore, RelationshipStore relStore, PropertyStore propStore,
                        RelationshipTypeTokenStore typeStore, LabelTokenStore labelTokenStore,
                        RelationshipGroupStore relGroupStore )
    {
        this.schemaStore = wrapStore( schemaStore );
        this.nodeStore = wrapStore( nodeStore );
        this.relStore = wrapStore( relStore );
        this.propStore = wrapStore( propStore );
        this.stringStore = wrapStore( propStore.getStringStore() );
        this.arrayStore = wrapStore( propStore.getArrayStore() );
        this.relationshipTypeTokenStore = wrapStore( typeStore );
        this.labelTokenStore = wrapStore( labelTokenStore );
        this.nodeDynamicLabelStore = wrapStore( wrapNodeDynamicLabelStore( nodeStore.getDynamicLabelStore() ) );
        this.propertyKeyTokenStore = wrapStore( propStore.getPropertyKeyTokenStore() );
        this.relationshipTypeNameStore = wrapStore( typeStore.getNameStore() );
        this.labelNameStore = wrapStore( labelTokenStore.getNameStore() );
        this.propertyKeyNameStore = wrapStore( propStore.getPropertyKeyTokenStore().getNameStore() );
        this.relGroupStore = wrapStore( relGroupStore );
    }

    public StoreAccess( PageCache pageCache, String path )
    {
        this( new DefaultFileSystemAbstraction(), pageCache, path );
    }

    public StoreAccess( FileSystemAbstraction fileSystem, PageCache pageCache, String path )
    {
        this( fileSystem, pageCache, path, new Config( requiredParams( defaultParams(), path )), new Monitors() );
    }

    private StoreAccess( FileSystemAbstraction fileSystem, PageCache pageCache, String path, Config config, Monitors monitors )
    {
        this( new StoreFactory( config, new DefaultIdGeneratorFactory(), pageCache,
                fileSystem, StringLogger.DEV_NULL, monitors ).newNeoStore( false ) );
        this.closeable = true;
    }

    private static Map<String, String> requiredParams( Map<String, String> params, String path )
    {
        return StoreFactory.configForStoreDir( new Config(), new File( path ) ).getParams();
    }

    public NeoStore getRawNeoStore()
    {
        return neoStore;
    }

    public RecordStore<DynamicRecord> getSchemaStore()
    {
        return schemaStore;
    }

    public RecordStore<NodeRecord> getNodeStore()
    {
        return nodeStore;
    }

    public RecordStore<RelationshipRecord> getRelationshipStore()
    {
        return relStore;
    }

    public RecordStore<RelationshipGroupRecord> getRelationshipGroupStore()
    {
        return relGroupStore;
    }

    public RecordStore<PropertyRecord> getPropertyStore()
    {
        return propStore;
    }

    public RecordStore<DynamicRecord> getStringStore()
    {
        return stringStore;
    }

    public RecordStore<DynamicRecord> getArrayStore()
    {
        return arrayStore;
    }

    public RecordStore<RelationshipTypeTokenRecord> getRelationshipTypeTokenStore()
    {
        return relationshipTypeTokenStore;
    }

    public RecordStore<LabelTokenRecord> getLabelTokenStore()
    {
        return labelTokenStore;
    }

    public RecordStore<DynamicRecord> getNodeDynamicLabelStore()
    {
        return nodeDynamicLabelStore;
    }

    public RecordStore<PropertyKeyTokenRecord> getPropertyKeyTokenStore()
    {
        return propertyKeyTokenStore;
    }

    public RecordStore<DynamicRecord> getRelationshipTypeNameStore()
    {
        return relationshipTypeNameStore;
    }

    public RecordStore<DynamicRecord> getLabelNameStore()
    {
        return labelNameStore;
    }

    public RecordStore<DynamicRecord> getPropertyKeyNameStore()
    {
        return propertyKeyNameStore;
    }

    public final <F extends Exception, P extends RecordStore.Processor<F>> P applyToAll( P processor ) throws F
    {
        for ( RecordStore<?> store : allStores() )
        {
            apply( processor, store );
        }
        return processor;
    }

    protected RecordStore<?>[] allStores()
    {
        if ( propStore == null )
        {
            // for when the property store isn't available (e.g. because the contained data in very sensitive)
            return new RecordStore<?>[]{ // no property stores
                    nodeStore, relStore,
                    relationshipTypeTokenStore, relationshipTypeNameStore,
                    labelTokenStore, labelNameStore, nodeDynamicLabelStore
            };
        }
        return new RecordStore<?>[]{
                schemaStore, nodeStore, relStore, propStore, stringStore, arrayStore,
                relationshipTypeTokenStore, propertyKeyTokenStore, labelTokenStore,
                relationshipTypeNameStore, propertyKeyNameStore, labelNameStore,
                nodeDynamicLabelStore
        };
    }

    private static RecordStore<DynamicRecord> wrapNodeDynamicLabelStore( RecordStore<DynamicRecord> store ) {
        return new DelegatingRecordStore<DynamicRecord>( store ) {
            @Override
            public <FAILURE extends Exception> void accept( Processor<FAILURE> processor, DynamicRecord record)
                    throws FAILURE
            {
                processor.processLabelArrayWithOwner( this, record );
            }
        };
    }

    protected <R extends AbstractBaseRecord> RecordStore<R> wrapStore( RecordStore<R> store )
    {
        return store;
    }

    @SuppressWarnings("unchecked")
    protected <FAILURE extends Exception> void apply( RecordStore.Processor<FAILURE> processor, RecordStore<?> store )
            throws FAILURE
    {
        processor.applyFiltered( store, RecordStore.IN_USE );
    }

    private static Map<String, String> defaultParams()
    {
        Map<String, String> params = new HashMap<>();
        params.put( GraphDatabaseSettings.mapped_memory_total_size.name(), "50%" );
        params.put( GraphDatabaseSettings.rebuild_idgenerators_fast.name(), Settings.TRUE );
        return params;
    }

    public synchronized void close()
    {
        if ( closeable )
        {
            closeable = false;
            neoStore.close();
        }
    }
}
