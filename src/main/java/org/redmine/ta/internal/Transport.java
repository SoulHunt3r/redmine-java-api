package org.redmine.ta.internal;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.NameValuePair;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicNameValuePair;
import org.redmine.ta.NotFoundException;
import org.redmine.ta.RedmineAuthenticationException;
import org.redmine.ta.RedmineException;
import org.redmine.ta.RedmineFormatException;
import org.redmine.ta.RedmineInternalError;
import org.redmine.ta.RedmineManager;
import org.redmine.ta.beans.Identifiable;
import org.redmine.ta.beans.Project;
import org.redmine.ta.internal.json.JsonFormatException;
import org.redmine.ta.internal.json.JsonInput;
import org.redmine.ta.internal.json.JsonObjectParser;
import org.redmine.ta.internal.json.JsonObjectWriter;
import org.redmine.ta.internal.logging.Logger;
import org.redmine.ta.internal.logging.LoggerFactory;

import com.google.gson.JsonObject;

/**
 * Redmine transport utilities.
 * 
 * @author maxkar
 * 
 */
public final class Transport {
	private static final Map<Class<?>, EntityConfig<?>> OBJECT_CONFIGS = new HashMap<Class<?>, EntityConfig<?>>();
	private static final String FORMAT_SUFFIX = ".json";
	private static final String CONTENT_TYPE = "application/json; charset=utf-8";
	private static final int DEFAULT_OBJECTS_PER_PAGE = 25;
	private static final String KEY_TOTAL_COUNT = "total_count";
	private final Logger logger = LoggerFactory.getLogger(RedmineManager.class);

	static {
		OBJECT_CONFIGS.put(
				Project.class,
				config("project", "projects",
						RedmineJSONBuilder.CREATE_PROJECT_WRITER,
						RedmineJSONParser.PROJECT_PARSER));
	}

	/** Uri configurator */
	private final URIConfigurator configurator;
	private String login;
	private String password;
	private boolean useBasicAuth = false;
	private int objectsPerPage = DEFAULT_OBJECTS_PER_PAGE;

	public Transport(URIConfigurator configurator) {
		this.configurator = configurator;
	}

	/**
	 * Performs an "add object" request.
	 * 
	 * @param object
	 *            object to use.
	 * @param params
	 *            name params.
	 * @return object to use.
	 * @throws RedmineException
	 *             if something goes wrong.
	 */
	public <T> T addObject(T object, NameValuePair... params)
			throws RedmineException {
		final EntityConfig<T> config = getConfig(object.getClass());
		URI uri = getURIConfigurator().createURI(
				config.multiObjectName + FORMAT_SUFFIX, params);
		HttpPost httpPost = new HttpPost(uri);
		String body = RedmineJSONBuilder.toSimpleJSON(config.singleObjectName,
				object, config.writer);
		setEntity(httpPost, body);
		String response = getCommunicator().sendRequest(httpPost);
		return parseResponce(response, config.singleObjectName, config.parser);
	}

	/*
	 * note: This method cannot return the updated object from Redmine because
	 * the server does not provide any XML in response.
	 * 
	 * @since 1.8.0
	 */
	public <T extends Identifiable> void updateObject(T obj,
			NameValuePair... params) throws RedmineException {
		final EntityConfig<T> config = getConfig(obj.getClass());
		final URI uri = getURIConfigurator().getItemURI(obj.getClass(),
				Integer.toString(obj.getId()));
		final HttpPut http = new HttpPut(uri);

		final String body = RedmineJSONBuilder.toSimpleJSON(
				config.singleObjectName, obj, config.writer);
		setEntity(http, body);

		getCommunicator().sendRequest(http);
	}


	/**
	 * Deletes an object.
	 * 
	 * @param classs
	 *            object class.
	 * @param id
	 *            object id.
	 * @throws RedmineException
	 *             if something goes wrong.
	 */
	public <T extends Identifiable> void deleteObject(Class<T> classs, String id)
			throws RedmineException {
		final URI uri = getURIConfigurator().getItemURI(classs, id);
		final HttpDelete http = new HttpDelete(uri);
		getCommunicator().sendRequest(http);
	}

	/**
	 * @param classs
	 *            target class
	 * @param key
	 *            item key
	 * @param args
	 *            extra arguments.
	 * @throws RedmineAuthenticationException
	 *             invalid or no API access key is used with the server, which
	 *             requires authorization. Check the constructor arguments.
	 * @throws NotFoundException
	 *             the object with the given key is not found
	 * @throws RedmineException
	 */
	public <T> T getObject(Class<T> classs, String key, NameValuePair... args)
			throws RedmineException {
		final EntityConfig<T> config = getConfig(classs);
		final URI uri = getURIConfigurator().getItemURI(classs, key, args);
		final HttpGet http = new HttpGet(uri);
		String response = getCommunicator().sendRequest(http);
		return parseResponce(response, config.singleObjectName, config.parser);
	}

	/**
	 * Returns an object list.
	 * 
	 * @return objects list, never NULL
	 */
	public <T> List<T> getObjectsList(Class<T> objectClass,
			NameValuePair... params) throws RedmineException {
		final EntityConfig<T> config = getConfig(objectClass);
		final List<T> result = new ArrayList<T>();

		final List<NameValuePair> newParams = new ArrayList<NameValuePair>(
				Arrays.asList(params));

		newParams.add(new BasicNameValuePair("limit", String
				.valueOf(objectsPerPage)));
		int offset = 0;

		int totalObjectsFoundOnServer;
		do {
			List<NameValuePair> paramsList = new ArrayList<NameValuePair>(
					newParams);
			paramsList.add(new BasicNameValuePair("offset", String
					.valueOf(offset)));

			final URI uri = getURIConfigurator().createURI(
					config.multiObjectName + FORMAT_SUFFIX, paramsList);

			logger.debug(uri.toString());
			final HttpGet http = new HttpGet(uri);

			final String response = getCommunicator().sendRequest(http);
			logger.debug("received: " + response);

			final List<T> foundItems;
			try {
				final JsonObject responceObject = RedmineJSONParser
						.getResponce(response);
				totalObjectsFoundOnServer = JsonInput.getInt(responceObject,
						KEY_TOTAL_COUNT);
				foundItems = JsonInput.getListOrNull(responceObject,
						config.multiObjectName, config.parser);
			} catch (JsonFormatException e) {
				throw new RedmineFormatException(e);
			}

			if (foundItems.size() == 0) {
				break;
			}
			result.addAll(foundItems);

			offset += foundItems.size();
		} while (offset < totalObjectsFoundOnServer);

		return result;
	}

	/**
	 * This number of objects (tasks, projects, users) will be requested from
	 * Redmine server in 1 request.
	 */
	public int getObjectsPerPage() {
		return objectsPerPage;
	}

	// TODO add test

	/**
	 * This number of objects (tasks, projects, users) will be requested from
	 * Redmine server in 1 request.
	 */
	public void setObjectsPerPage(int pageSize) {
		if (pageSize <= 0) {
			throw new IllegalArgumentException(
					"Page size must be >= 0. You provided: " + pageSize);
		}
		this.objectsPerPage = pageSize;
	}

	private Communicator getCommunicator() {
		Communicator communicator = new Communicator();
		if (useBasicAuth) {
			communicator.setCredentials(login, password);
		}
		return communicator;
	}

	private static <T> T parseResponce(String responce, String tag,
			JsonObjectParser<T> parser) throws RedmineFormatException {
		try {
			return parser.parse(RedmineJSONParser.getResponceSingleObject(
					responce, tag));
		} catch (JsonFormatException e) {
			throw new RedmineFormatException(e);
		}
	}

	private void setEntity(HttpEntityEnclosingRequest request, String body) {
		StringEntity entity;
		try {
			entity = new StringEntity(body, Communicator.CHARSET);
		} catch (UnsupportedEncodingException e) {
			throw new RedmineInternalError("Required charset "
					+ Communicator.CHARSET + " is not supported", e);
		}
		entity.setContentType(CONTENT_TYPE);
		request.setEntity(entity);
	}

	@SuppressWarnings("unchecked")
	private <T> EntityConfig<T> getConfig(Class<?> class1) {
		final EntityConfig<?> guess = OBJECT_CONFIGS.get(class1);
		if (guess == null)
			throw new RedmineInternalError("Unsupported class " + class1);
		return (EntityConfig<T>) guess;
	}

	private URIConfigurator getURIConfigurator() {
		return configurator;
	}

	private static <T> EntityConfig<T> config(String objectField,
			String urlPrefix, JsonObjectWriter<T> writer,
			JsonObjectParser<T> parser) {
		return new EntityConfig<T>(objectField, urlPrefix, writer, parser);
	}

	public void setCredentials(String login, String password) {
		this.login = login;
		this.password = password;
		this.useBasicAuth = true;
	}

	/**
	 * Entity config.
	 * 
	 * @author maxkar
	 * 
	 */
	static class EntityConfig<T> {
		final String singleObjectName;
		final String multiObjectName;
		final JsonObjectWriter<T> writer;
		final JsonObjectParser<T> parser;

		public EntityConfig(String objectField, String urlPrefix,
				JsonObjectWriter<T> writer, JsonObjectParser<T> parser) {
			super();
			this.singleObjectName = objectField;
			this.multiObjectName = urlPrefix;
			this.writer = writer;
			this.parser = parser;
		}

	}
}
