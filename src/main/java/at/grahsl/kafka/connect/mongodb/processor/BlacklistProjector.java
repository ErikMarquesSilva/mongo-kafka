package at.grahsl.kafka.connect.mongodb.processor;

import at.grahsl.kafka.connect.mongodb.MongoDbSinkConnectorConfig;
import com.mongodb.DBCollection;
import org.apache.kafka.connect.sink.SinkRecord;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import java.util.Set;

public class BlacklistProjector extends FieldProjector {

    public BlacklistProjector(MongoDbSinkConnectorConfig config,
                              Set<String> fields) {
        super(config);
        this.fields = fields;
    }

    @Override
    public void process(BsonDocument doc, SinkRecord orig) {
        //NOTE: never try to remove _id field
        fields.forEach(f -> {
            if (!f.equalsIgnoreCase(DBCollection.ID_FIELD_NAME))
                handleBlackListEntry(f,doc);
        });
        next.ifPresent(pp -> pp.process(doc,orig));
    }

    private void handleBlackListEntry(String field,BsonDocument doc) {

        if(!field.contains(FieldProjector.SUB_FIELD_DOT_SEPARATOR)) {
            doc.remove(field);
            return;
        }

        int dotIdx = field.indexOf(FieldProjector.SUB_FIELD_DOT_SEPARATOR);
        String firstPart = field.substring(0,dotIdx);
        BsonValue value = doc.get(firstPart);
        if(value!=null && value.isDocument()) {
            if(field.length() >= dotIdx) {
                String otherParts = field.substring(dotIdx+1);
                handleBlackListEntry(otherParts,(BsonDocument)value);
            }
        }

    }
}
