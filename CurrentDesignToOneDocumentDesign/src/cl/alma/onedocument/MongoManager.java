package cl.alma.onedocument;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;

import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.CommandResult;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.WriteResult;
import com.mongodb.util.JSON;

/**
 * Manages the connection to MongoDB and provides all methods that the TMC 
 * needs to use the schema "One monitor point per day per document".
 *
 * @author Leonel Peña <leo.dhpl@gmail.com>
 *
 */
public class MongoManager implements Runnable {
	
	public static final int N_MONITOR_POINTS = 250000;
	public static final String NOT_ASSIGNED = "na";
	public static final String DEFAULT_CHARACTER = "a";
	public static final int DEFAULT_PREALLOCATE_TIME = 1;
	public static final int MAX_VALUE_SIZE = 7;
	
	private static final Logger log = Logger.getLogger(MongoManager.class);
	private static final Logger infoLog = Logger.getLogger("info_log");
	
	private static Mongo mongo;
	private static DB database;
	private static Map<String, DBCollection> mongoCollections;
	
	private static int numThreads;
	private static DocumentBuffer<String> documentBuffer;
	private static AtomicInteger preallocate_cont;
	private static AtomicInteger updates;
	private static AtomicInteger errors;

	private String threadName;
	private LinkedBlockingQueue<DBObject> queue;
	
	private static BasicDBObject[] preallocatedDocuments;
	
	static {
		numThreads = 0;
		preallocate_cont = new AtomicInteger();
		updates = new AtomicInteger();
		errors = new AtomicInteger();
		mongoCollections = Collections.synchronizedMap(
				new HashMap<String, DBCollection>(3)
		);
		documentBuffer = new DocumentBuffer<String>(N_MONITOR_POINTS);
		
		// Creates several documents with different size of the value
		preallocatedDocuments = new BasicDBObject[MAX_VALUE_SIZE]; 
		for (int i=0; i<MAX_VALUE_SIZE; i++) {
			preallocatedDocuments[i] = preAllocate(i+1);
		}
	}

	/**
	 * Returns a MongoManager instance. Use this method to get the
	 * MongoManager objects to use in a multiple-thread consumer environment.
	 * @param queue
	 * @return 
	 */
	public static MongoManager mongoManagerFactory(LinkedBlockingQueue<DBObject> queue) {
		numThreads++;

		return new MongoManager("MongoManager_"+numThreads, queue);
	}
	
	/**
	 * Returns the preallocate document with predefine value size.
	 * @param valueSize Value size of the fields
	 * @return
	 */
	public static BasicDBObject getPreallocatedDocument(int valueSize) {
		if (valueSize<0 || valueSize>=MAX_VALUE_SIZE)
			throw new IllegalArgumentException("Value size out of range.");
		
		return preallocatedDocuments[valueSize];
	}
	
	public static BasicDBObject getPreallocatedDocument(Metadata metadata, int valueSize) {
		if (valueSize<0 || valueSize>=MAX_VALUE_SIZE)
			throw new IllegalArgumentException("Value size out of range.");
		
		BasicDBObject newDocument = (BasicDBObject) preallocatedDocuments[valueSize].clone();
		
		newDocument.append("_id",metadata.getDocumentID().toString());

		newDocument.append("metadata", new BasicDBObject().append(
				"date", metadata.getDocumentID().getStringDate()).append(
				"antenna", metadata.getDocumentID().getAntenna()).append(
				"component", metadata.getDocumentID().getComponent()).append(
				"property", metadata.getProperty()).append(
				"monitorPoint", metadata.getDocumentID().getMonitorPoint()).append(
				"location", metadata.getLocation()).append(
				"serialNumber", metadata.getSerialNumber()).append(
				"index", metadata.getIndex()).append(
				"sampleTime", metadata.getSampleTime())
		);
		
		return newDocument;
	}
	
	/**
	 * 
	 * @param _mongo
	 * @param _database
	 */
	public static void setConnection(Mongo _mongo, DB _database) {
		mongo = _mongo;
		database = _database;
	}
	
	private MongoManager(String threadName, LinkedBlockingQueue<DBObject> queue) {
		this.threadName = threadName;
		this.queue = queue;
	}

	@Deprecated
	public MongoManager(String host, String dbname) 
					throws UnknownHostException {

		mongo = new Mongo(host);
		database = mongo.getDB(dbname);
		mongoCollections = new HashMap<String, DBCollection>(3);
		//mongoCollections = new HashMap<Integer, DBCollection>(70);
		//collection = database.getCollection("monitorData");
		//collection = database.getCollection(coll);
		//createdDocuments = new HashMap<String, Boolean>(N_MONITOR_POINTS);
		
		// Error with atomiccounter
		//preallocate_cont = 0;
		//this.documentBuffer = new DocumentBuffer<String>(N_MONITOR_POINTS);
	}
	
	public void setQueue(LinkedBlockingQueue<DBObject> queue) {
		this.queue = queue;
	}

	/**
	 * Returns the collection to which the document belongs. <br/>
	 * This method uses a monthly collection per monitor point. <br/>
	 * If the collection does not exist it is created along with its index 
	 * and shard key.
	 * 
	 * @param id Document id
	 * @return Collection
	 */
	public DBCollection getCollection(DocumentID id) {

		String key = Integer.toString(id.getMonth()) + "_" + 
				Integer.toString(id.getYear());
		
		if (mongoCollections.containsKey(key))
			return mongoCollections.get(key);

		BasicDBObject index = null;
		BasicDBObject shardKey = null;
		if (!database.collectionExists("monitorData_"+key)) {

			// Setting the index
			index = new BasicDBObject("metadata.date", 1);
			index.append("metadata.antenna", 1);
			index.append("metadata.component", 1);
			index.append("metadata.monitorPoint", 1);
			
			// Setting the shard key
			shardKey = new BasicDBObject("metadata.date", 1);
			shardKey.append("metadata.antenna", 1);
		}

		// If the collection does not exist, it will be created automatically 
		// by MongoDB
		DBCollection c = database.getCollection("monitorData_"+key);
		mongoCollections.put(key, c);
		
		if (index!=null) {
			//c.createIndex(index);
			c.ensureIndex(index, "dateMonitorPointAntennaComponent");
		}
		
		if (shardKey!=null) {
			DB admin = mongo.getDB("admin");
			CommandResult result = null;

			result = admin.command(new BasicDBObject("OneMonitorPointPerDayPerDocument","1"));
			result = admin.command(new BasicDBObject("monitorData_"+key,"1"));
			
			DBObject keys = new BasicDBObject();
			keys.put("metadata.date", 1);
			keys.put("metadata.antenna", 1);

			DBObject cmd = new BasicDBObject();
			cmd.put("shardcollection", "OneMonitorPointPerDayPerDocument.monitorData_"+key);
			cmd.put("key", keys);
			result = admin.command(cmd);
		}

		return c;
	}

	/**
	 * Update or insert a sample. It is highly recommended preallocate a 
	 * document before upsert it. If is upsert a document to which is not
	 * preallocated the insert/update performance will decrease considerably.
	 * If you turn on the preallocate parameter, this method manage 
	 * the preallocate operation and you do not take care about that. 
	 * 
	 * @param sample Sample to insert or update
	 * @param preallocate <i>true</i> activate the automatic preallocation 
	 * control and <i>false</i> for disable the preallocation manage
	 */
	public void upsert(Sample sample, boolean preallocate) {

		Metadata meta = sample.getMetadata();
		DocumentID docID = meta.getDocumentID();
		DBCollection collection = getCollection(docID);

		// Monitor data value to update
		String attribute = "hourly." + Integer.toString(sample.getHour()) + 
				"." + Integer.toString(sample.getMinute()) + 
				"." + Integer.toString(sample.getSecond());

		Metadata metadata = sample.getMetadata();
		BasicDBObject document = new BasicDBObject().append("_id",
				metadata.getDocumentID().toString());

		// Revisar si es que es necesario enviar todos los metadatos
		document.append("metadata", new BasicDBObject().append(
				"date", metadata.getDocumentID().getStringDate()).append(
				"antenna", metadata.getDocumentID().getAntenna()).append(
				"component", metadata.getDocumentID().getComponent()).append(
				"property", metadata.getProperty()).append(
				"monitorPoint", metadata.getDocumentID().getMonitorPoint()).append(
				"location", metadata.getLocation()).append(
				"serialNumber", metadata.getSerialNumber()).append(
				"index", metadata.getIndex()).append(
				"sampleTime", metadata.getSampleTime())
		);
		/*document.append("metadata", new BasicDBObject().append(
				"date", metadata.getDocumentID().getStringDate()).append(
				"antenna", metadata.getDocumentID().getAntenna()).append(
				"component", metadata.getDocumentID().getComponent()).append(
				"monitorPoint", metadata.getDocumentID().getMonitorPoint())
		);*/

		BasicDBObject updateDocument = new BasicDBObject().append("$set",
				new BasicDBObject().append(attribute, sample.getValue()));

		// Preallocating the document
		if (preallocate && !isDocumentCreated(docID, false)) {
			// By default the document begins in 00:00:00.
			Calendar tStart = new GregorianCalendar(docID.getYear(),
					docID.getMonth(), docID.getDay(), 0, 0, 0);
			collection.insert(preAllocate(meta, tStart.getTime(), 
					sample.getValue().length())
			);
			
			// Registering the document to the buffer
			registerDocumentToBuffer(docID);
		}

		collection.update(document, updateDocument, true, false);
	}
	
	/**
	 * Update or insert a list of samples. It is highly recommended preallocate a 
	 * document before upsert it. If is upsert a document to which is not
	 * preallocated the insert/update performance will decrease considerably.
	 * If you turn on the preallocate parameter, this method manage 
	 * the preallocate operation and you do not take care about that. 
	 * 
	 * @param samples List of samples to insert or update
	 * @param preallocate <i>true</i> activate the automatic preallocation 
	 * control and <i>false</i> for disable the preallocation manage
	 */
	public void upsert(List<Sample> samples, boolean preallocate) {
		
		if (samples==null || samples.isEmpty()) {
			throw new IllegalArgumentException("List of samples cannot be null or empty");
		}

		DBCollection collection = null;

		DocumentID docID = null;
		Metadata metadata = null;
		BasicDBObject document = null;

		// Monitor data value to update
		String attribute = null;
		
		// Store all monitor data
		BasicDBObjectBuilder sets = new BasicDBObjectBuilder();

		for (Sample sample : samples) {

			metadata = sample.getMetadata();
			docID = metadata.getDocumentID();
			collection = getCollection(docID);

			// Monitor data value to update
			attribute = "hourly." + sample.getHour() + "." + 
						sample.getMinute() + "." + sample.getSecond();

			sets.add(attribute, sample.getValue());
		}

		metadata = samples.get(0).getMetadata();
		document = new BasicDBObject().append("_id",
				metadata.getDocumentID().toString());

		// Revisar si es que es necesario enviar todos los metadatos
		document.append("metadata", new BasicDBObject().append(
				"date", metadata.getDocumentID().getStringDate()).append(
				"antenna", metadata.getDocumentID().getAntenna()).append(
				"component", metadata.getDocumentID().getComponent()).append(
				"property", metadata.getProperty()).append(
				"monitorPoint", metadata.getDocumentID().getMonitorPoint()).append(
				"location", metadata.getLocation()).append(
				"serialNumber", metadata.getSerialNumber()).append(
				"index", metadata.getIndex()).append(
				"sampleTime", metadata.getSampleTime())
		);

		// Preallocating the document
		if (preallocate && !isDocumentCreated(docID, false)) {
			// By default the document begins in 00:00:00.
			Calendar tStart = new GregorianCalendar(docID.getYear(),
					docID.getMonth(), docID.getDay(), 0, 0, 0);

			collection.insert(preAllocate(metadata, tStart.getTime(), 
					samples.get(0).getValue().length()
			));
			
			// Registering the document to the buffer
			registerDocumentToBuffer(docID);
		}

		collection.update(document, new BasicDBObject("$set",sets.get()), 
				true, false);
	}

	/**
	 * Close the connection with MongoDB
	 */
	public void close() {
		mongo.close();
	}

	/**
	 * Returns the thread name
	 * @return
	 */
	public String getThreadName() {
		return this.threadName;
	}

	/**
	 * Creates a document with the necessary structure for a post-update of 
	 * its attributes. Use it before upsert a document. This method does not 
	 * register the preallocated method in the internal buffer. There are two
	 * ways to do that:
	 * <br/>
	 * 1) The automatic way: Set to <i>true</i> the preallocate argument of
	 * the method upsert and let to the system the responsibility to manage it.
	 * <br/>
	 * 2) The manual way: If you want to manage the preallocate operation you
	 * need to use these methods: isDocumentCreated(...), registerPreallocation(...)  
	 * and preAllocate(...)
	 * 
	 * @param metadata Document metadatas
	 * @param tStart The time to start the preallocation
	 * @param valueSize Size of the value that will be post-update
	 * @return The preallocated document
	 */
	public DBObject preAllocate(Metadata metadata, Date tStart, int valueSize) {

		// If the value size is equal or less than two, it use the 
		// NOT_ASSIGNED string to represent a not assigned value and
		// for the values size greater or equal than three use the NOT_ASSIGNED 
		// constant plus DEFAULT_CHARACTER for each character greater or 
		// equal than three. In the second case, the size of the 
		// value preallocated is equal to the size that will be assigned into 
		// the upsert method
		String valueToPreallocate = NOT_ASSIGNED;
		for (int i=3; i<=valueSize; i++) {
			valueToPreallocate += DEFAULT_CHARACTER;
		}

		BasicDBObject preAllocatedDocument = new BasicDBObject().append("_id",
				metadata.getDocumentID().toString());

		preAllocatedDocument.append("metadata", new BasicDBObject().append(
				"date", metadata.getDocumentID().getStringDate()).append(
				"antenna", metadata.getDocumentID().getAntenna()).append(
				"component", metadata.getDocumentID().getComponent()).append(
				"property", metadata.getProperty()).append(
				"monitorPoint", metadata.getDocumentID().getMonitorPoint()).append(
				"location", metadata.getLocation()).append(
				"serialNumber", metadata.getSerialNumber()).append(
				"index", metadata.getIndex()).append(
				"sampleTime", metadata.getSampleTime())
		);

		DocumentID doc = metadata.getDocumentID();
		Calendar timeStart = new GregorianCalendar();
		timeStart.setTime(tStart);

		int last_hour = timeStart.get(Calendar.HOUR_OF_DAY);
		int last_minute = timeStart.get(Calendar.MINUTE);
		int last_second = timeStart.get(Calendar.SECOND);
		
		int current_hour = 0;
		int current_minute = 0;
		int current_second = 0;

		Calendar time = new GregorianCalendar(doc.getYear(), doc.getMonth(),
				doc.getDay(), last_hour, last_minute, last_second);

		BasicDBObjectBuilder hours = new BasicDBObjectBuilder();
		BasicDBObjectBuilder minutes = new BasicDBObjectBuilder();
		BasicDBObjectBuilder seconds = new BasicDBObjectBuilder();

		// The document is just for one day
		while (time.get(Calendar.DAY_OF_MONTH)==timeStart.get(Calendar.DAY_OF_MONTH)) {

			current_hour = time.get(Calendar.HOUR_OF_DAY);
			current_minute = time.get(Calendar.MINUTE);
			current_second = time.get(Calendar.SECOND);

			// If the "hour" change creates a new instance of minute and second,
			// but if only changed the minute creates a new instance of seconds
			if (last_hour!=current_hour) {

				minutes.add(Integer.toString(last_minute),seconds.get());
				hours.add(Integer.toString(last_hour),minutes.get());
				
				minutes = new BasicDBObjectBuilder();
				seconds = new BasicDBObjectBuilder();

			} else if (last_minute!=current_minute) {

				minutes.add(Integer.toString(last_minute),seconds.get());
				seconds = new BasicDBObjectBuilder();
			}
			
			//seconds.add(Integer.toString(current_second), NOT_ASSIGNED);
			seconds.add(Integer.toString(current_second), valueToPreallocate);

			last_hour = current_hour;
			last_minute = current_minute;
			last_second = current_second;
			
			time.add(Calendar.SECOND, metadata.getSampleTime());
		}

		// Appending the remaining minutes and seconds
		minutes.add(Integer.toString(last_minute),seconds.get());
		hours.add(Integer.toString(last_hour),minutes.get());
		preAllocatedDocument.put("hourly", hours.get());

		return preAllocatedDocument;
	}
	
	/**
	 * Creates a document with the necessary structure for a post-update of 
	 * its attributes. This method creates a document with all seconds, minutes 
	 * and hours of a day. Use it before upsert a document. This method does not 
	 * register the preallocated method in the internal buffer. There are two
	 * ways to do that:
	 * <br/>
	 * 1) The automatic way: Set to <i>true</i> the preallocate argument of
	 * the method upsert and let to the system the responsibility to manage it.
	 * <br/>
	 * 2) The manual way: If you want to manage the preallocate operation you
	 * need to use these methods: isDocumentCreated(...), registerPreallocation(...)  
	 * and preAllocate(...)
	 * 
	 * @param metadata Document metadatas
	 * @param valueSize Size of the value that will be post-update
	 * @return The preallocated document
	 */
	public DBObject preAllocate(Metadata metadata, int valueSize) {

		// If the value size is equal or less than two, it use the 
		// NOT_ASSIGNED string to represent a not assigned value and
		// for the values size greater or equal than three use the NOT_ASSIGNED 
		// constant plus DEFAULT_CHARACTER for each character greater or 
		// equal than three. In the second case, the size of the 
		// value preallocated is equal to the size that will be assigned into 
		// the upsert method
		String valueToPreallocate = NOT_ASSIGNED;
		for (int i=3; i<=valueSize; i++) {
			valueToPreallocate += DEFAULT_CHARACTER;
		}
		
		DocumentID doc = metadata.getDocumentID();

		BasicDBObject preAllocatedDocument = new BasicDBObject().append("_id",
				metadata.getDocumentID().toString());

		preAllocatedDocument.append("metadata", new BasicDBObject().append(
				"date", doc.getStringDate()).append(
				"antenna", doc.getAntenna()).append(
				"component", doc.getComponent()).append(
				"property", metadata.getProperty()).append(
				"monitorPoint", doc.getMonitorPoint()).append(
				"location", metadata.getLocation()).append(
				"serialNumber", metadata.getSerialNumber()).append(
				"index", metadata.getIndex()).append(
				"sampleTime", metadata.getSampleTime())
		);
		
		BasicDBObjectBuilder hours = new BasicDBObjectBuilder();
		BasicDBObjectBuilder minutes = new BasicDBObjectBuilder();
		BasicDBObjectBuilder seconds = new BasicDBObjectBuilder();
		
		for (int hour=0; hour<24; hour++) {
			for (int minute=0; minute<60; minute++) {
				for (int second=0; second<60; second++) {
					seconds.add(Integer.toString(second), valueToPreallocate);
				}
				minutes.add(Integer.toString(minute), seconds.get());
			}
			hours.add(Integer.toString(hour), minutes.get());
		}

		preAllocatedDocument.put("hourly", hours.get());

		return preAllocatedDocument;
	}
	
	/**
	 * Creates a document with the necessary structure for a post-update of 
	 * its attributes. This method creates a document with all seconds, minutes 
	 * and hours of a day. Use it before upsert a document. This method does not 
	 * register the preallocated method in the internal buffer. There are two
	 * ways to do that:
	 * <br/>
	 * 1) The automatic way: Set to <i>true</i> the preallocate argument of
	 * the method upsert and let to the system the responsibility to manage it.
	 * <br/>
	 * 2) The manual way: If you want to manage the preallocate operation you
	 * need to use these methods: isDocumentCreated(...), registerPreallocation(...)  
	 * and preAllocate(...)
	 * 
	 * @param metadata Document metadatas
	 * @param valueSize Size of the value that will be post-update
	 * @return The preallocated document
	 */
	private static BasicDBObject preAllocate(int valueSize) {

		// If the value size is equal or less than two, it use the 
		// NOT_ASSIGNED string to represent a not assigned value and
		// for the values size greater or equal than three use the NOT_ASSIGNED 
		// constant plus DEFAULT_CHARACTER for each character greater or 
		// equal than three. In the second case, the size of the 
		// value preallocated is equal to the size that will be assigned into 
		// the upsert method
		String valueToPreallocate = NOT_ASSIGNED;
		for (int i=3; i<=valueSize; i++) {
			valueToPreallocate += DEFAULT_CHARACTER;
		}
		
		BasicDBObject preAllocatedDocument = new BasicDBObject();
		BasicDBObjectBuilder hours = new BasicDBObjectBuilder();
		BasicDBObjectBuilder minutes = new BasicDBObjectBuilder();
		BasicDBObjectBuilder seconds = new BasicDBObjectBuilder();
		
		for (int hour=0; hour<24; hour++) {
			for (int minute=0; minute<60; minute++) {
				for (int second=0; second<60; second++) {
					seconds.add(Integer.toString(second), valueToPreallocate);
				}
				minutes.add(Integer.toString(minute), seconds.get());
			}
			hours.add(Integer.toString(hour), minutes.get());
		}

		preAllocatedDocument.put("hourly", hours.get());

		return preAllocatedDocument;
	}

	/**
	 * Verifies if a document exist in the collection of the database.
	 * The method consult the buffer first and if the document does not 
	 * exist consult the database. <br/>
	 * This method does not register into the buffer a document that does not 
	 * exist into the database. 
	 * 
	 * @param id Document id
	 * @return <i>true</i> if the document exist and <i>false</i> otherwise
	 */
	public boolean isDocumentCreated(DocumentID id, boolean useBuffer) {
		
		// First, check the buffer
		if (useBuffer && documentBuffer.contains(id.toString()))
			return true;

		// Otherwise consult to the database
		DBCollection coll = getCollection(id);
		DBObject doc = coll.findOne(new BasicDBObject("_id",id.toString()));
		if (doc!=null) {
			if (useBuffer) {
				documentBuffer.set(id.toString());
			}

			return true;
		}

		return false;
	}

	/**
	 * Register a document to the buffer for decrease the database query time.
	 * Before preallocate a document you must consult the database if that 
	 * document exist or not, in order to improve that operation use this method
	 * that used a buffer to maintain the daily created documents and in that 
	 * way you do not need to consult the database every time. <br/>
	 * This method does not validate if a document exist into the buffer.
	 * 
	 * @param documentID The document to register into the buffer
	 */
	public void registerDocumentToBuffer(DocumentID documentID) {

		// Registering the document to the buffer
		//documentBuffer.set(documentID.toString());
		preallocate_cont.incrementAndGet();
	}

	@Override
	public void run() {
		//int cont=0, error=0;
		boolean done = false;
		while (!done) {
			try {
				DBObject object = queue.take();

				Map<String,Object> myMap = object.toMap();
				
				Calendar calendar = Calendar.getInstance();
				try {
					calendar.setTime((Date)myMap.get("date"));
				} catch (NullPointerException e) {
					System.err.println("Object: "+myMap);
				}

			    // ************************************************ //
			    // Se añaden las tres horas de diferencia			//
			    // con el servidor de mongo.						//
			    // ************************************************	//
			    calendar.add(Calendar.HOUR, 3);

			    int year = calendar.get(Calendar.YEAR);
			    int month = calendar.get(Calendar.MONTH)+1;
			    int day = calendar.get(Calendar.DAY_OF_MONTH);
			    
			    int hour = calendar.get(Calendar.HOUR_OF_DAY);
			    int minute =  calendar.get(Calendar.MINUTE);
			    int second =  calendar.get(Calendar.SECOND);
			    
			    //System.out.println("Map: "+myMap+",\n year: "+year+", month: "+
			    	//	month+", day: "+day+", hour: "+hour+", minute: "+minute+
			    		//", second: "+second);
			    
			    //if (day==29) {
				  //  log.info("year: "+year+", month: "+
				    //		month+", day: "+day+", hour: "+hour+", minute: "+
				    	//	minute+", second: "+second);
			    //}
			    
			    // We need to split the componentName that comes from the old schema.
			    // The format is "CONTROL/DV10/FrontEnd/Cryostat".
			    // We extract the antenna, component and subcomponent 
			    // from it.
			    String[] oldComponentName = ((String)myMap.get("componentName")).split("/");
			    String antenna = oldComponentName[1];
			    String component = null;

			    // If the old ComponentName length is three it means there is
			    // just a component, however, if the length is four
			    // there is a component and subcomponent
			    if (oldComponentName.length==2) {
			    	// This case occurs when the component name is 
			    	// "ACACORR/CCC_MONITOR"
			    	antenna = oldComponentName[0];
			    	component = oldComponentName[1];

			    } else if (oldComponentName.length==3) {
			    	component = oldComponentName[2];

			    } else if (oldComponentName.length==4) {
			    	component = oldComponentName[2]+"/"+oldComponentName[3];

		    	} else {
		    		log.error("Something detected in component name: "+
		    				myMap.get("componentName"));
		    	}

			    String property = (String)myMap.get("propertyName");
			    String monitorPoint = (String)myMap.get("monitorPointName");
			    String location = (String)myMap.get("location");
			    String serialNumber = (String)myMap.get("serialNumber");
			    String monitorValue = (String)myMap.get("monitorValue");
			    int index = Integer.parseInt(myMap.get("index").toString());
			    int sampleTime = MongoManager.DEFAULT_PREALLOCATE_TIME;

				DocumentID documentID = new DocumentID(year, month, day,
						antenna, component, monitorPoint);

				Metadata metadata = new Metadata(documentID, property, 
						location, serialNumber, index, sampleTime);
				
				Sample sample = new Sample(metadata, hour, minute, second, 
						monitorValue);
				
				//mongoManager.upsert(metadata, 14, 4, 6, "12345");
				//upsert(metadata, hour, minute, second, monitorValue);
				upsert(sample, true);
				
				if (updates.incrementAndGet()==10000) {
					infoLog.info("Registros insertados: "+updates.get());
					infoLog.info("Preallocate document: "+preallocate_cont.get());
				}

			} catch (InterruptedException e) {
				//close();
				infoLog.info("Preallocated documents: "+preallocate_cont.get());
				infoLog.info("Registros insertados: "+updates.get());
				log.info("Errores: "+errors.get());
				
				done = true;

			} catch (Throwable e) {
				errors.incrementAndGet();
				log.error("Exception caught: "+e.getMessage());
				log.error(Arrays.toString(e.getStackTrace()));
			}
		}
	}
	
	/*
	@Override
	public void run() {
		SynchronousQuery synQuery = null;
		try {
			synQuery = new SynchronousQuery("mongo-r1.osf.alma.cl", "MONDB",
					"monitorPoints");
		} catch (UnknownHostException e1) {
			System.exit(-1);
		}

		DBCursor cursor = synQuery.exportData();

		int cont=0, error=0;
		while (cursor.hasNext()) {
			try {
				//DBObject object = queue.take();
				DBObject object = cursor.next();
	
				//Set<String> mySet = object.keySet();
				Map<String, Object> myMap = object.toMap();
				//System.out.println("Set: "+mySet);
				
	
				//if (myMap.get("date") instanceof Date) {
					//System.out.println("Es instacia de DATE!!");
				//} else {
					//System.out.println("NO lo es!");
				//}
				
				Calendar calendar = Calendar.getInstance();
				try {
					calendar.setTime((Date)myMap.get("date"));
				} catch (NullPointerException e) {
					log.error("NullPointerException: "+myMap);
					error++;
				}
			    
			    // ************************************************ //
			    // Se añaden las tres horas de diferencia 			//
			    // con el servidor de mongo.						//
			    // ************************************************	//
			    calendar.add(Calendar.HOUR, 3);
	
			    int year = calendar.get(Calendar.YEAR);
			    int month = calendar.get(Calendar.MONTH)+1;
			    int day = calendar.get(Calendar.DAY_OF_MONTH);
			    
			    int hour = calendar.get(Calendar.HOUR_OF_DAY);
			    int minute =  calendar.get(Calendar.MINUTE);
			    int second =  calendar.get(Calendar.SECOND);
			    
			    //System.out.println("Map: "+myMap+",\n year: "+year+", month: "+
			    	//	month+", day: "+day+", hour: "+hour+", minute: "+minute+
			    		//", second: "+second);
			    
			    // We need to split the componentName that comes from the old schema.
			    // The format is "CONTROL/DV10/FrontEnd/Cryostat".
			    // We extract the antenna, component and subcomponent 
			    // from it.
			    String[] oldComponentName = ((String)myMap.get("componentName")).split("/");
			    String antenna = oldComponentName[1];
			    // Component and subcomponent are put together
			    String component = null;
			    
			    // If the old ComponentName length is three it means there is
			    // just a component, however, if the length is four
			    // there is a component and subcomponent
			    if (oldComponentName.length==3) {
			    	component = oldComponentName[2];
			    } else if (oldComponentName.length==4) {
			    	component = oldComponentName[2]+"/"+oldComponentName[3];
		    	} else {
		    		log.error("Error detected in component name: "+myMap.get("componentName"));
		    	}

			    //if (oldComponentName.length>3) {
			    	//component = oldComponentName[2]+"/"+oldComponentName[3];
			    //} else {
			    	//component = oldComponentName[2];
			    //}
			    
			    String property = (String)myMap.get("propertyName");
			    String monitorPoint = (String)myMap.get("monitorPointName");
			    String location = (String)myMap.get("location");
			    String serialNumber = (String)myMap.get("serialNumber");
			    String monitorValue = (String)myMap.get("monitorValue");
			    int index = Integer.parseInt(myMap.get("index").toString());
			    int sampleTime = 0;
	
				DocumentID documentID = new DocumentID(year, month, day,
						antenna, component, monitorPoint);
				//Metadata metadata = new Metadata(documentID, "ASDF Property", "TFING",
					//	"as76d6fh", 5, 2);
				Metadata metadata = new Metadata(documentID, property, 
						location, serialNumber, index, sampleTime);
				
				//mongoManager.upsert(metadata, 14, 4, 6, "12345");
				upsert(metadata, hour, minute, second, monitorValue);
				cont++;
				//insert(record);
			} catch (Throwable e) {
				error++;
				log.error("Exception caught: "+e.getMessage());
				log.error(Arrays.toString(e.getStackTrace()));
			}
		}

		synQuery.closeCursor();
		close();
		log.info("Registros insertados: "+cont);
		log.info("Errores: "+error);
	} */	
	
}
