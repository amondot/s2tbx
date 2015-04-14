package org.esa.s2tbx.framework.gpf.descriptor;

import org.esa.s2tbx.framework.gpf.operators.tooladapter.ToolAdapterConstants;
import org.esa.s2tbx.ui.tooladapter.utils.PropertyAttributeException;
import org.esa.snap.framework.gpf.descriptor.DefaultParameterDescriptor;

import java.lang.reflect.Method;

/**
 * @author Ramona Manda
 */
public class ToolParameterDescriptor extends DefaultParameterDescriptor {

    private String parameterType = ToolAdapterConstants.REGULAR_PARAM_MASK;

    public ToolParameterDescriptor(){
        super();
    }

    public ToolParameterDescriptor(String name, Class<?> type){
        super(name, type);
    }

    public ToolParameterDescriptor(DefaultParameterDescriptor object) {
        super(object.getName(), object.getDataType());
        super.setAlias(object.getAlias());
        super.setDefaultValue(object.getDefaultValue());
        super.setDescription(object.getDescription());
        super.setLabel(object.getLabel());
        super.setUnit(object.getUnit());
        super.setInterval(object.getInterval());
        super.setValueSet(object.getValueSet());
        super.setCondition(object.getCondition());
        super.setPattern(object.getPattern());
        super.setFormat(object.getFormat());
        super.setNotNull(object.isNotNull());
        super.setNotEmpty(object.isNotEmpty());
        super.setRasterDataNodeClass(object.getRasterDataNodeClass());
        super.setValidatorClass(object.getValidatorClass());
        super.setConverterClass(object.getConverterClass());
        super.setDomConverterClass(object.getDomConverterClass());
        super.setItemAlias(object.getItemAlias());
        parameterType = ToolAdapterConstants.REGULAR_PARAM_MASK;
    }

    public ToolParameterDescriptor(DefaultParameterDescriptor object, String parameterTypeMask) {
        this(object);
        this.parameterType = parameterTypeMask;
    }

    //TODO throws specific exception, also in the calling methods!
    public Object getAttribute(String propertyName) throws PropertyAttributeException {
        Method getter;
        try {
            //TODO
            getter = DefaultParameterDescriptor.class.getDeclaredMethod("is" + propertyName.substring(0, 1).toUpperCase() + propertyName.substring(1));
            return getter.invoke(this);
        } catch (Exception ex) {
        }
        //the "is..." getter could not be called
        try {
            getter = DefaultParameterDescriptor.class.getDeclaredMethod("get" + propertyName.substring(0, 1).toUpperCase() + propertyName.substring(1));
            return getter.invoke(this);
        } catch (Exception e) {
            throw new PropertyAttributeException("Exception on getting the value of the attribute '" + propertyName + "' message: " + e.getMessage());
        }
    }

    public void setAttribute(String propertyName, Object obj) throws PropertyAttributeException {
        Method setter;
        try {
            setter = DefaultParameterDescriptor.class.getDeclaredMethod("set" + propertyName.substring(0, 1).toUpperCase() + propertyName.substring(1), obj.getClass());
            setter.invoke(this, obj);
        } catch (Exception e) {
            throw new PropertyAttributeException("Exception on setting the value '" + obj.toString() + "' to the attribute '" + propertyName + "' message: " + e.getMessage());
        }
    }

    public String getParameterType() {
        if(this.parameterType == null){
            this.parameterType = ToolAdapterConstants.REGULAR_PARAM_MASK;
        }
        return this.parameterType;
    }

    public boolean isTemplateParameter() {
        return getParameterType().equals(ToolAdapterConstants.TEMPLATE_PARAM_MASK);
    }

    public boolean isTemplateBefore() {
        return getParameterType().equals(ToolAdapterConstants.TEMPLATE_BEFORE_MASK);
    }

    public boolean isTemplateAfter() {
        return getParameterType().equals(ToolAdapterConstants.TEMPLATE_AFTER_MASK);
    }

    public boolean isParameter() {
        return getParameterType().equals(ToolAdapterConstants.REGULAR_PARAM_MASK);
    }

    public void setParameterType(String type) {
        this.parameterType = type;
    }

}