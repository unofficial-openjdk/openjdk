/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package sun.management;

import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import javax.management.openmbean.ArrayType;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularType;
import static sun.management.Util.toStringArray;

/**
 *
 * @author Jaroslav Bachorik
 */
final class TypeVersionMapper {
    private static final class Singleton {
        private final static TypeVersionMapper INSTANCE = new TypeVersionMapper();
    }

    final static String V5 = "J2SE 5.0";
    final static String V6 = "Java SE 6";

    private final Map<String, Map<String, Predicate<String>>> filterMap;

    private TypeVersionMapper() {
        filterMap = new HashMap<>();
        setupStackTraceElement();
        setupThreadInfo();
    }

    public static TypeVersionMapper getInstance() {
        return Singleton.INSTANCE;
    }

    private void setupStackTraceElement() {
        Map<String, Predicate<String>> filter = new HashMap<>();
        filterMap.put(StackTraceElement.class.getName(), filter);
        filter.put(V5, StackTraceElementCompositeData::isV6Attribute);
        filter.put(V6, StackTraceElementCompositeData::isV6Attribute);
    }

    private void setupThreadInfo() {
        Map<String, Predicate<String>> filter = new HashMap<>();
        filterMap.put(ThreadInfo.class.getName(), filter);
        filter.put(V5, ThreadInfoCompositeData::isV5Attribute);
        filter.put(V6, ThreadInfoCompositeData::isV6Attribute);
    }

    CompositeType getVersionedCompositeType(CompositeType type, String version)
    throws OpenDataException {
        Predicate<String> filter = getFilter(type.getTypeName(), version);
        if (filter == null) {
            return type;
        }

        List<String> itemNames = new ArrayList<>();
        List<String> itemDesc = new ArrayList<>();
        List<OpenType<?>> itemTypes = new ArrayList<>();

        for(String item : type.keySet()) {
            if (filter.test(item)) {
                itemNames.add(item);
                itemDesc.add(type.getDescription(item));
                itemTypes.add(getVersionedType(
                    type.getType(item),
                    version
                ));
            }
        }
        return new CompositeType(
            type.getTypeName(),
            version != null ? version + " " + type.getDescription() : type.getDescription(),
            itemNames.toArray(new String[itemNames.size()]),
            itemDesc.toArray(new String[itemDesc.size()]),
            itemTypes.toArray(new OpenType<?>[itemTypes.size()])
        );
    }

    private OpenType<?> getVersionedType(OpenType<?> type, String version)
    throws OpenDataException{
        if (type instanceof ArrayType) {
            return getVersionedArrayType((ArrayType)type, version);
        }
        if (type instanceof CompositeType) {
            return getVersionedCompositeType((CompositeType)type, version);
        }
        if (type instanceof TabularType) {
            return getVersionedTabularType((TabularType)type, version);
        }
        return type;
    }

    private ArrayType<?> getVersionedArrayType(ArrayType<?> type, String version)
    throws OpenDataException {
        if (type.isPrimitiveArray()) {
            return type;
        }
        OpenType<?> ot = getVersionedType(
            type.getElementOpenType(),
            version
        );
        if (ot instanceof SimpleType) {
            return new ArrayType<>((SimpleType<?>)ot, type.isPrimitiveArray());
        } else {
            return new ArrayType<>(type.getDimension(), ot);
        }
    }

    private TabularType getVersionedTabularType(
        TabularType type, String version)
    throws OpenDataException {
        CompositeType ct = getVersionedCompositeType(
            type.getRowType(),
            version
        );

        if (ct != null) {
            return new TabularType(
                type.getTypeName(), type.getDescription(), ct,
                toStringArray(type.getIndexNames()));
        }
        return null;
    }

    private Predicate<String> getFilter(String type, String version) {
        Map<String, Predicate<String>> versionMap = filterMap.get(type);
        if (versionMap == null) {
            return null;
        }

        return versionMap.get(version);
    }
}
