package org.lumongo.fields;

import org.bson.Document;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SavedFieldInfo<T> {
	private final String fieldName;
	private final Field field;
	private boolean fieldIsList;

	public SavedFieldInfo(Field field, String fieldName) {
		this.fieldName = fieldName;
		this.field = field;
		this.fieldIsList = List.class.isAssignableFrom(field.getType());
	}

	public String getFieldName() {
		return fieldName;
	}

	public Object getValue(T object) throws Exception {

		Object o = field.get(object);

		return o;
	}

	public void populate(T newInstance, Document savedDBObject) throws Exception {

		Object value = savedDBObject.get(fieldName);

		boolean valuesIsList = value instanceof List;

		if (valuesIsList) {
			if (fieldIsList) {
				field.set(newInstance, new ArrayList<>((List<?>) value));
			}
			else {
				List<?> valueList = (List<?>) value;
				if (valueList.size() == 1) {
					Object first = valueList.iterator().next();
					if (first != null) {
						field.set(newInstance, first);
					}
				}
				else if (valueList.isEmpty()) {

				}
				else {
					throw new Exception("Cannot assign multiple values <" + valueList + "> to field <" + field.getName() + "> with type <" + field.getType()
									+ "> because it is not a list.");
				}
			}
		}
		else {
			if (fieldIsList) {
				if (value != null) {
					field.set(newInstance, new ArrayList<>(Collections.singletonList(value)));
				}
			}
			else {
				field.set(newInstance, value);
			}
		}

	}
}
