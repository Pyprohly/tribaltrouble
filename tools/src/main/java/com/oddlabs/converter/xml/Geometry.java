package com.oddlabs.converter.xml;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import org.jspecify.annotations.NonNull;

import javax.xml.stream.XMLInputFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** The whole of geometry.xml. The parser validates against the file's own DTD, then every element binds to a record. */
public record Geometry(@JacksonXmlElementWrapper(useWrapping = false) @JacksonXmlProperty(localName = "group") @NonNull List<Group> groups) {

    public Geometry {
        if (groups == null)
            groups = List.of();
    }

    public static @NonNull Geometry read(@NonNull Path file) throws IOException {
        XmlMapper mapper = XmlMapper.builder().enable(
                MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS).build();
        mapper.getFactory().getXMLInputFactory().setProperty(XMLInputFactory.SUPPORT_DTD, true);
        mapper.getFactory().getXMLInputFactory().setProperty(XMLInputFactory.IS_VALIDATING, true);
        return mapper.readValue(file.toFile(), Geometry.class);
    }
}
