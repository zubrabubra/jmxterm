package org.cyclopsgroup.jmxterm.cmd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.management.JMException;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.apache.commons.lang3.Validate;
import org.cyclopsgroup.jcli.annotation.Argument;
import org.cyclopsgroup.jcli.annotation.Cli;
import org.cyclopsgroup.jcli.annotation.MultiValue;
import org.cyclopsgroup.jcli.annotation.Option;
import org.cyclopsgroup.jmxterm.Command;
import org.cyclopsgroup.jmxterm.Session;
import org.cyclopsgroup.jmxterm.SyntaxUtils;
import org.cyclopsgroup.jmxterm.io.ValueOutputFormat;
import org.cyclopsgroup.jmxterm.utils.ValueFormat;

/**
 * Command to run an MBean operation
 *
 * @author <a href="mailto:jiaqi.guo@gmail.com">Jiaqi Guo</a>
 */
@Cli( name = "run", description = "Invoke an MBean operation", note = "Syntax is \n run <operationName> [parameter1] [parameter2]" )
public class RunCommand
    extends Command
{
    private String bean;

    private String domain;

    private boolean measure;

    private List<String> parameters = Collections.emptyList();

    private boolean showQuotationMarks;

    private String types;

    /**
     * @inheritDoc
     */
    @Override
    public List<String> doSuggestArgument()
        throws IOException, JMException
    {
        Session session = getSession();
        if ( getSession().getBean() != null )
        {
            MBeanInfo info =
                session.getConnection().getServerConnection().getMBeanInfo( new ObjectName(
                                                                                            session.getBean() ) );
            MBeanOperationInfo[] operationInfos = info.getOperations();
            List<String> ops = new ArrayList<String>( operationInfos.length );
            for ( MBeanOperationInfo op : operationInfos )
            {
                ops.add( op.getName() );
            }
            return ops;
        }
        return null;
    }

    /**
     * @inheritDoc
     */
    @Override
    public void execute()
        throws MalformedObjectNameException, IOException, JMException
    {
        Session session = getSession();
        String beanName = BeanCommand.getBeanName( bean, domain, session );
        if ( beanName == null )
        {
            throw new IllegalArgumentException(
                                                "Please specify MBean to invoke either using -b option or bean command" );
        }

        Validate.isTrue( parameters.size() > 0,
                         "At least one parameter is needed" );
        String[] paramTypes = null;
        if ( types != null )
        {
            paramTypes = types.split( "," );
            Validate.isTrue( paramTypes.length == parameters.size() - 1,
                             "Signature does not match parameter count" );
        }
        String operationName = parameters.get( 0 );
        ObjectName name = new ObjectName( beanName );
        MBeanServerConnection con =
            session.getConnection().getServerConnection();
        MBeanInfo beanInfo = con.getMBeanInfo( name );

        // Looking for operation to invoke
        MBeanOperationInfo operationInfo = null;
        for ( MBeanOperationInfo info : beanInfo.getOperations() )
        {
            if ( operationName.equals( info.getName() )
                && info.getSignature().length == parameters.size() - 1 )
            {
                // If operation name and number of parameters matches, optionally check parameter types
                if ( paramTypes == null )
                {
                    operationInfo = info;
                    break;
                }
                // If paramTypes parameter is set, narrow down operation with parameter matching
                boolean match = true;
                MBeanParameterInfo[] paramInfos = info.getSignature();
                for ( int i = 0; i < paramTypes.length && i < paramInfos.length; i++ )
                {
                    // String type is treated specially
                    // type "string" implies type "java.lang.String"
                    if ( paramInfos[i].getType().equals( String.class.getName() )
                        && paramTypes[i].equals( "string" ) )
                    {
                        continue;
                    }
                    if ( !paramTypes[i].equals( paramInfos[i].getType() ) )
                    {
                        match = false;
                        break;
                    }
                }
                if ( match )
                {
                    operationInfo = info;
                    break;
                }
            }
        }
        // If no matching operation is found, throw an exception
        if ( operationInfo == null )
        {
            throw new IllegalArgumentException( "Operation " + operationName
                + " with " + ( parameters.size() - 1 )
                + " parameters doesn't exist in bean " + beanName );
        }

        // Now set parameters to invoke with
        Object[] params = new Object[parameters.size() - 1];
        MBeanParameterInfo[] paramInfos = operationInfo.getSignature();
        Validate.isTrue( params.length == paramInfos.length,
                         String.format( "%d parameters are expected but %d are provided",
                                        paramInfos.length, params.length ) );
        String[] signatures = new String[paramInfos.length];
        for ( int i = 0; i < paramInfos.length; i++ )
        {
            MBeanParameterInfo paramInfo = paramInfos[i];
            String expression = parameters.get( i + 1 );
            if ( expression != null )
            {
                expression = ValueFormat.parseValue( expression );
            }
            Object paramValue =
                SyntaxUtils.parse( expression, paramInfo.getType() );
            params[i] = paramValue;
            signatures[i] = paramInfo.getType();
        }
        session.output.printMessage( String.format( "calling operation %s of mbean %s with params %s",
                operationName, beanName, Arrays.toString(params) ) );


        // Invoke operation, record execution time if measure flag is on
        Object result;
        if ( measure )
        {
            long start = System.currentTimeMillis();
            try
            {
                result = con.invoke( name, operationName, params, signatures );
            }
            finally
            {
                long latency = System.currentTimeMillis() - start;
                session.output.printMessage( latency
                    + "ms is taken by invocation" );
            }
        }
        else
        {
            result = con.invoke( name, operationName, params, signatures );
        }
        session.output.printMessage( "operation returns: " );
        new ValueOutputFormat( 2, false, showQuotationMarks ).printValue( session.output,
                                                                          result );
        // Finish with an empty line
        session.output.println( "" );
    }

    /**
     * @param bean Bean under which the operation is
     */
    @Option( name = "b", longName = "bean", description = "MBean to invoke" )
    public final void setBean( String bean )
    {
        this.bean = bean;
    }

    /**
     * @param domain Domain under which is bean is
     */
    @Option( name = "d", longName = "domain", description = "Domain of MBean to invoke" )
    public final void setDomain( String domain )
    {
        this.domain = domain;
    }

    /**
     * @param measure True if you want to display latency
     */
    @Option( name = "m", longName = "measure", description = "Measure the time spent on the invocation of operation" )
    public final void setMeasure( boolean measure )
    {
        this.measure = measure;
    }

    /**
     * @param types
     */
    @Option( name = "t", longName = "types", description = "Require parameters to have specific types (comma separated)" )
    public final void setTypes( String types )
    {
        this.types = types;
    }

    /**
     * @param parameters List of parameters. The first parameter is operation name
     */
    @MultiValue( listType = ArrayList.class, minValues = 1 )
    @Argument( description = "The first parameter is operation name, which is followed by list of arguments" )
    public final void setParameters( List<String> parameters )
    {
        Validate.notNull( parameters, "Parameters can't be NULL" );
        this.parameters = parameters;
    }

    /**
     * @param showQuotationMarks True if output is surrounded by quotation marks
     */
    @Option( name = "q", longName = "quots", description = "Flag for quotation marks" )
    public final void setShowQuotationMarks( boolean showQuotationMarks )
    {
        this.showQuotationMarks = showQuotationMarks;
    }
}
