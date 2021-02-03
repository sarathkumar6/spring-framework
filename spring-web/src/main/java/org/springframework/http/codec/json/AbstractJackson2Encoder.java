/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.http.codec.json;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.ByteArrayBuilder;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SequenceWriter;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.ser.FilterProvider;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.core.codec.CodecException;
import org.springframework.core.codec.EncodingException;
import org.springframework.core.codec.Hints;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.log.LogFormatUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageEncoder;
import org.springframework.http.converter.json.MappingJacksonValue;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeType;

/**
 * Base class providing support methods for Jackson 2.9 encoding. For non-streaming use
 * cases, {@link Flux} elements are collected into a {@link List} before serialization for
 * performance reason.
 *
 * @author Sebastien Deleuze
 * @author Arjen Poutsma
 * @since 5.0
 */
public abstract class AbstractJackson2Encoder extends Jackson2CodecSupport implements HttpMessageEncoder<Object> {

	private static final byte[] NEWLINE_SEPARATOR = {'\n'};

	private static final Map<String, JsonEncoding> ENCODINGS;

	static {
		ENCODINGS = CollectionUtils.newHashMap(JsonEncoding.values().length);
		for (JsonEncoding encoding : JsonEncoding.values()) {
			ENCODINGS.put(encoding.getJavaName(), encoding);
		}
		ENCODINGS.put("US-ASCII", JsonEncoding.UTF8);
	}


	private final List<MediaType> streamingMediaTypes = new ArrayList<>(1);


	/**
	 * Constructor with a Jackson {@link ObjectMapper} to use.
	 */
	protected AbstractJackson2Encoder(ObjectMapper mapper, MimeType... mimeTypes) {
		super(mapper, mimeTypes);
	}


	/**
	 * Configure "streaming" media types for which flushing should be performed
	 * automatically vs at the end of the stream.
	 */
	public void setStreamingMediaTypes(List<MediaType> mediaTypes) {
		this.streamingMediaTypes.clear();
		this.streamingMediaTypes.addAll(mediaTypes);
	}


	@Override
	public boolean canEncode(ResolvableType elementType, @Nullable MimeType mimeType) {
		if (!supportsMimeType(mimeType)) {
			return false;
		}
		if (mimeType != null && mimeType.getCharset() != null) {
			Charset charset = mimeType.getCharset();
			if (!ENCODINGS.containsKey(charset.name())) {
				return false;
			}
		}
		ObjectMapper mapper = selectObjectMapper(elementType, mimeType);
		if (mapper == null) {
			return false;
		}
		Class<?> clazz = elementType.toClass();
		if (String.class.isAssignableFrom(elementType.resolve(clazz))) {
			return false;
		}
		if (Object.class == clazz) {
			return true;
		}
		if (!logger.isDebugEnabled()) {
			return mapper.canSerialize(clazz);
		}
		else {
			AtomicReference<Throwable> causeRef = new AtomicReference<>();
			if (mapper.canSerialize(clazz, causeRef)) {
				return true;
			}
			logWarningIfNecessary(clazz, causeRef.get());
			return false;
		}
	}

	@Override
	public Flux<DataBuffer> encode(Publisher<?> inputStream, DataBufferFactory bufferFactory,
			ResolvableType elementType, @Nullable MimeType mimeType, @Nullable Map<String, Object> hints) {

		Assert.notNull(inputStream, "'inputStream' must not be null");
		Assert.notNull(bufferFactory, "'bufferFactory' must not be null");
		Assert.notNull(elementType, "'elementType' must not be null");

		if (inputStream instanceof Mono) {
			return Mono.from(inputStream)
					.map(value -> encodeValue(value, bufferFactory, elementType, mimeType, hints))
					.flux();
		}
		else {
			byte[] separator = getStreamingMediaTypeSeparator(mimeType);
			if (separator != null) { // streaming
				try {
					ObjectMapper mapper = selectObjectMapper(elementType, mimeType);
					if (mapper == null) {
						throw new IllegalStateException("No ObjectMapper for " + elementType);
					}
					ObjectWriter writer = createObjectWriter(mapper, elementType, mimeType, null, hints);
					ByteArrayBuilder byteBuilder = new ByteArrayBuilder(writer.getFactory()._getBufferRecycler());
					JsonEncoding encoding = getJsonEncoding(mimeType);
					JsonGenerator generator = mapper.getFactory().createGenerator(byteBuilder, encoding);
					SequenceWriter sequenceWriter = writer.writeValues(generator);

					return Flux.from(inputStream)
							.map(value -> encodeStreamingValue(value, bufferFactory, hints, sequenceWriter, byteBuilder,
									separator))
							.doAfterTerminate(() -> {
								try {
									byteBuilder.release();
									generator.close();
								}
								catch (IOException ex) {
									logger.error("Could not close Encoder resources", ex);
								}
							});
				}
				catch (IOException ex) {
					return Flux.error(ex);
				}
			}
			else { // non-streaming
				ResolvableType listType = ResolvableType.forClassWithGenerics(List.class, elementType);
				return Flux.from(inputStream)
						.collectList()
						.map(list -> encodeValue(list, bufferFactory, listType, mimeType, hints))
						.flux();
			}

		}
	}

	@Override
	public DataBuffer encodeValue(Object value, DataBufferFactory bufferFactory,
			ResolvableType valueType, @Nullable MimeType mimeType, @Nullable Map<String, Object> hints) {

		ObjectMapper mapper = selectObjectMapper(valueType, mimeType);
		if (mapper == null) {
			throw new IllegalStateException("No ObjectMapper for " + valueType);
		}
		Class<?> jsonView = null;
		FilterProvider filters = null;
		if (value instanceof MappingJacksonValue) {
			MappingJacksonValue container = (MappingJacksonValue) value;
			value = container.getValue();
			jsonView = container.getSerializationView();
			filters = container.getFilters();
		}
		ObjectWriter writer = createObjectWriter(mapper, valueType, mimeType, jsonView, hints);
		if (filters != null) {
			writer = writer.with(filters);
		}
		ByteArrayBuilder byteBuilder = new ByteArrayBuilder(writer.getFactory()._getBufferRecycler());
		try {
			JsonEncoding encoding = getJsonEncoding(mimeType);

			logValue(hints, value);

			try (JsonGenerator generator = mapper.getFactory().createGenerator(byteBuilder, encoding)) {
				writer.writeValue(generator, value);
				generator.flush();
			}
			catch (InvalidDefinitionException ex) {
				throw new CodecException("Type definition error: " + ex.getType(), ex);
			}
			catch (JsonProcessingException ex) {
				throw new EncodingException("JSON encoding error: " + ex.getOriginalMessage(), ex);
			}
			catch (IOException ex) {
				throw new IllegalStateException("Unexpected I/O error while writing to byte array builder", ex);
			}

			byte[] bytes = byteBuilder.toByteArray();
			DataBuffer buffer = bufferFactory.allocateBuffer(bytes.length);
			buffer.write(bytes);
			Hints.touchDataBuffer(buffer, hints, logger);

			return buffer;
		}
		finally {
			byteBuilder.release();
		}
	}

	private DataBuffer encodeStreamingValue(Object value, DataBufferFactory bufferFactory, @Nullable Map<String, Object> hints,
			SequenceWriter sequenceWriter, ByteArrayBuilder byteArrayBuilder, byte[] separator) {

		logValue(hints, value);

		try {
			sequenceWriter.write(value);
			sequenceWriter.flush();
		}
		catch (InvalidDefinitionException ex) {
			throw new CodecException("Type definition error: " + ex.getType(), ex);
		}
		catch (JsonProcessingException ex) {
			throw new EncodingException("JSON encoding error: " + ex.getOriginalMessage(), ex);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Unexpected I/O error while writing to byte array builder", ex);
		}

		byte[] bytes = byteArrayBuilder.toByteArray();
		byteArrayBuilder.reset();

		int offset;
		int length;
		if (bytes.length > 0 && bytes[0] == ' ') {
			// SequenceWriter writes an unnecessary space in between values
			offset = 1;
			length = bytes.length - 1;
		}
		else {
			offset = 0;
			length = bytes.length;
		}
		DataBuffer buffer = bufferFactory.allocateBuffer(length + separator.length);
		buffer.write(bytes, offset, length);
		buffer.write(separator);
		Hints.touchDataBuffer(buffer, hints, logger);

		return buffer;
	}

	private void logValue(@Nullable Map<String, Object> hints, Object value) {
		if (!Hints.isLoggingSuppressed(hints)) {
			LogFormatUtils.traceDebug(logger, traceOn -> {
				String formatted = LogFormatUtils.formatValue(value, !traceOn);
				return Hints.getLogPrefix(hints) + "Encoding [" + formatted + "]";
			});
		}
	}

	private ObjectWriter createObjectWriter(
			ObjectMapper mapper, ResolvableType valueType, @Nullable MimeType mimeType,
			@Nullable Class<?> jsonView, @Nullable Map<String, Object> hints) {

		JavaType javaType = getJavaType(valueType.getType(), null);
		if (jsonView == null && hints != null) {
			jsonView = (Class<?>) hints.get(Jackson2CodecSupport.JSON_VIEW_HINT);
		}
		ObjectWriter writer = (jsonView != null ? mapper.writerWithView(jsonView) : mapper.writer());
		if (javaType.isContainerType()) {
			writer = writer.forType(javaType);
		}
		return customizeWriter(writer, mimeType, valueType, hints);
	}

	protected ObjectWriter customizeWriter(ObjectWriter writer, @Nullable MimeType mimeType,
			ResolvableType elementType, @Nullable Map<String, Object> hints) {

		return writer;
	}

	/**
	 * Return the separator to use for the given mime type.
	 * <p>By default, this method returns new line {@code "\n"} if the given
	 * mime type is one of the configured {@link #setStreamingMediaTypes(List)
	 * streaming} mime types.
	 * @since 5.3
	 */
	@Nullable
	protected byte[] getStreamingMediaTypeSeparator(@Nullable MimeType mimeType) {
		for (MediaType streamingMediaType : this.streamingMediaTypes) {
			if (streamingMediaType.isCompatibleWith(mimeType)) {
				return NEWLINE_SEPARATOR;
			}
		}
		return null;
	}

	/**
	 * Determine the JSON encoding to use for the given mime type.
	 * @param mimeType the mime type as requested by the caller
	 * @return the JSON encoding to use (never {@code null})
	 * @since 5.0.5
	 */
	protected JsonEncoding getJsonEncoding(@Nullable MimeType mimeType) {
		if (mimeType != null && mimeType.getCharset() != null) {
			Charset charset = mimeType.getCharset();
			JsonEncoding result = ENCODINGS.get(charset.name());
			if (result != null) {
				return result;
			}
		}
		return JsonEncoding.UTF8;
	}


	// HttpMessageEncoder

	@Override
	public List<MimeType> getEncodableMimeTypes() {
		return getMimeTypes();
	}

	@Override
	public List<MediaType> getStreamingMediaTypes() {
		return Collections.unmodifiableList(this.streamingMediaTypes);
	}

	@Override
	public Map<String, Object> getEncodeHints(@Nullable ResolvableType actualType, ResolvableType elementType,
			@Nullable MediaType mediaType, ServerHttpRequest request, ServerHttpResponse response) {

		return (actualType != null ? getHints(actualType) : Hints.none());
	}


	// Jackson2CodecSupport

	@Override
	protected <A extends Annotation> A getAnnotation(MethodParameter parameter, Class<A> annotType) {
		return parameter.getMethodAnnotation(annotType);
	}

}
