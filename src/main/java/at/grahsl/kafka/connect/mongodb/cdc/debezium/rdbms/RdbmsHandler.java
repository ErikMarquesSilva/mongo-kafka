/*
 * Copyright 2008-present MongoDB, Inc.
 * Copyright 2017 Hans-Peter Grahsl.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package at.grahsl.kafka.connect.mongodb.cdc.debezium.rdbms;

import at.grahsl.kafka.connect.mongodb.MongoDbSinkConnectorConfig;
import at.grahsl.kafka.connect.mongodb.cdc.CdcOperation;
import at.grahsl.kafka.connect.mongodb.cdc.debezium.DebeziumCdcHandler;
import at.grahsl.kafka.connect.mongodb.cdc.debezium.OperationType;
import at.grahsl.kafka.connect.mongodb.converter.SinkDocument;
import com.mongodb.client.model.WriteModel;
import org.apache.kafka.connect.errors.DataException;
import org.bson.BsonDocument;
import org.bson.BsonInvalidOperationException;
import org.bson.BsonObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class RdbmsHandler extends DebeziumCdcHandler {
    private static final String ID_FIELD = "_id";
    private static final String JSON_DOC_BEFORE_FIELD = "before";
    private static final String JSON_DOC_AFTER_FIELD = "after";
    private static final Logger LOGGER = LoggerFactory.getLogger(RdbmsHandler.class);

    public RdbmsHandler(final MongoDbSinkConnectorConfig config) {
        super(config);
        final Map<OperationType, CdcOperation> operations = new HashMap<>();
        operations.put(OperationType.CREATE, new RdbmsInsert());
        operations.put(OperationType.READ, new RdbmsInsert());
        operations.put(OperationType.UPDATE, new RdbmsUpdate());
        operations.put(OperationType.DELETE, new RdbmsDelete());
        registerOperations(operations);
    }

    public RdbmsHandler(final MongoDbSinkConnectorConfig config,
                        final Map<OperationType, CdcOperation> operations) {
        super(config);
        registerOperations(operations);
    }

    @Override
    public Optional<WriteModel<BsonDocument>> handle(final SinkDocument doc) {

        BsonDocument keyDoc = doc.getKeyDoc().orElseGet(BsonDocument::new);

        BsonDocument valueDoc = doc.getValueDoc().orElseGet(BsonDocument::new);

        if (valueDoc.isEmpty()) {
            LOGGER.debug("skipping debezium tombstone event for kafka topic compaction");
            return Optional.empty();
        }

        return Optional.of(getCdcOperation(valueDoc)
                .perform(new SinkDocument(keyDoc, valueDoc)));
    }

    static BsonDocument generateFilterDoc(final BsonDocument keyDoc, final BsonDocument valueDoc, final OperationType opType) {
        if (keyDoc.keySet().isEmpty()) {
            if (opType.equals(OperationType.CREATE) || opType.equals(OperationType.READ)) {
                //create: no PK info in keyDoc -> generate ObjectId
                return new BsonDocument(ID_FIELD, new BsonObjectId());
            }
            //update or delete: no PK info in keyDoc -> take everything in 'before' field
            try {
                BsonDocument filter = valueDoc.getDocument(JSON_DOC_BEFORE_FIELD);
                if (filter.isEmpty()) {
                    throw new BsonInvalidOperationException("value doc before field is empty");
                }
                return filter;
            } catch (BsonInvalidOperationException exc) {
                throw new DataException("error: value doc 'before' field is empty or has invalid type"
                        + " for update/delete operation which seems severely wrong -> defensive actions taken!", exc);
            }
        }
        //build filter document composed of all PK columns
        BsonDocument pk = new BsonDocument();
        for (String f : keyDoc.keySet()) {
            pk.put(f, keyDoc.get(f));
        }
        return new BsonDocument(ID_FIELD, pk);
    }

    static BsonDocument generateUpsertOrReplaceDoc(final BsonDocument keyDoc, final BsonDocument valueDoc, final BsonDocument filterDoc) {

        if (!valueDoc.containsKey(JSON_DOC_AFTER_FIELD) || valueDoc.get(JSON_DOC_AFTER_FIELD).isNull()
                || !valueDoc.get(JSON_DOC_AFTER_FIELD).isDocument() || valueDoc.getDocument(JSON_DOC_AFTER_FIELD).isEmpty()) {
            throw new DataException("error: valueDoc must contain non-empty 'after' field"
                    + " of type document for insert/update operation");
        }

        BsonDocument upsertDoc = new BsonDocument();
        if (filterDoc.containsKey(ID_FIELD)) {
            upsertDoc.put(ID_FIELD, filterDoc.get(ID_FIELD));
        }

        BsonDocument afterDoc = valueDoc.getDocument(JSON_DOC_AFTER_FIELD);
        for (String f : afterDoc.keySet()) {
            if (!keyDoc.containsKey(f)) {
                upsertDoc.put(f, afterDoc.get(f));
            }
        }
        return upsertDoc;
    }

}