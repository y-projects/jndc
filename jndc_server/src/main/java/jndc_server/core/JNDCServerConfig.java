package jndc_server.core;

import ch.qos.logback.classic.Level;
import jndc.core.UniqueBeanManage;
import jndc.core.data_store_support.DBWrapper;
import jndc.core.data_store_support.DataStore;
import jndc.utils.*;
import jndc_server.databases_object.IpFilterRule4V;
import jndc_server.web_support.core.MappingRegisterCenter;
import jndc_server.web_support.core.MessageNotificationCenter;
import jndc_server.web_support.http_module.HostRouterComponent;
import jndc_server.web_support.utils.AuthUtils;
import jndc_server.web_support.utils.SslOneWayContextFactory;
import lombok.Data;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Data
public class JNDCServerConfig {

    private static final String UN_SUPPORT_VALUE = "jndc";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private String runtimeDir="";

    private String secrete;

    private String loglevel;

    private String routNotFoundPage="\uD83D\uDEEB\uD83D\uDEEB\uD83D\uDEEBNot Found";

    private boolean scanFrontPages = false;

    private int managementApiPort;

    private int httpPort;

    private int adminPort;

    private String bindIp;

    private InetAddress inetAddress;

    private InetSocketAddress inetSocketAddress;

    private InetSocketAddress httpInetSocketAddress;

    private String[] blackList;

    private String[] whiteList;

    private String loginName;

    private String loginPassWord;

    private boolean useSsl;

    private String keyStoreFile;

    private String keystorePass;

    private SSLContext serverSSLContext;


    /**
     * 参数校验
     */
    public void performParameterVerification() {
        inetAddress = InetUtils.getByStringIpAddress(bindIp);
        inetSocketAddress = new InetSocketAddress(inetAddress, adminPort);
        httpInetSocketAddress=new InetSocketAddress(inetAddress, httpPort);

        if (UN_SUPPORT_VALUE.equals(getLoginName()) && UN_SUPPORT_VALUE.equals(getLoginPassWord())) {
            LogPrint.err("the default name and password 'jndc' is not safe,please edit the config file and retry");
            ApplicationExit.exit();
        }
        AuthUtils.name = getLoginName();
        AuthUtils.passWord = getLoginPassWord();

        //perform ssl file
        performSslInWebApi();


        //register bean
        UniqueBeanManage.registerBean(this);

        UniqueBeanManage.registerBean(new NDCServerConfigCenter());
        UniqueBeanManage.registerBean(new IpChecker());
        UniqueBeanManage.registerBean(new MappingRegisterCenter());
        UniqueBeanManage.registerBean(new DataStore(getRuntimeDir()));
        UniqueBeanManage.registerBean(new AsynchronousEventCenter());
        UniqueBeanManage.registerBean(new MessageNotificationCenter());
        UniqueBeanManage.registerBean(new ScheduledTaskCenter());
        UniqueBeanManage.registerBean(new HostRouterComponent());

        //do server init
        init();
    }


    static{
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.INFO);
    }

    private void init(){
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.toLevel(getLoglevel()));

        //set secrete
        AESUtils.setKey(secrete.getBytes());


    }

    private void performSslInWebApi() {
        if (isUseSsl()) {
            try {
                byte[]  bytes = FileUtils.readFileToByteArray(new File(getKeyStoreFile()));
                reloadSslContext(bytes, getKeystorePass().toCharArray());
                logger.info("open ssl in the web api");
            } catch (Exception e) {
                setUseSsl(false);
                logger.error("init ssl context  fail cause:" + e);
            }


        }
    }

    public void reloadSslContext(byte[] keyStore, char[] keyStorePass) {
        try {
            serverSSLContext = SslOneWayContextFactory.getServerContext(new ByteArrayInputStream(keyStore),keyStorePass);
        } catch (Exception e) {
            setUseSsl(false);
            logger.error("load ssl context  fail cause:" + e);
            throw new RuntimeException(e);
        }
    }


    //fix tag

    /**
     * lazy init
     */
    public void lazyInitAfterVerification() {
        initIpChecker();
    }


    private void initIpChecker(){
        DBWrapper<IpFilterRule4V> dbWrapper = DBWrapper.getDBWrapper(IpFilterRule4V.class);
        List<IpFilterRule4V> ipFilterRule4VS = dbWrapper.listAll();
        Map<String, IpFilterRule4V> blackMap = new HashMap<>();
        Map<String, IpFilterRule4V> whiteMap = new HashMap<>();
        ipFilterRule4VS.forEach(x -> {
            if (x.isBlack()) {
                blackMap.put(x.getIp(), x);
            } else {
                whiteMap.put(x.getIp(), x);
            }
        });


        IpChecker ipChecker = UniqueBeanManage.getBean(IpChecker.class);
        if (blackList == null) {
            blackList = new String[0];
        }

        if (whiteList == null) {
            whiteList = new String[0];
        }

        List<IpFilterRule4V> storeList = new ArrayList<>();

        Stream.of(blackList).forEach(x -> {
            if (!blackMap.containsKey(x)) {

                IpFilterRule4V ipFilterRule4V = new IpFilterRule4V();
                ipFilterRule4V.black();
                ipFilterRule4V.setId(UUIDSimple.id());
                ipFilterRule4V.setIp(x);
                blackMap.put(x, ipFilterRule4V);
                storeList.add(ipFilterRule4V);
            }
        });

        Stream.of(whiteList).forEach(x -> {
            if (!whiteMap.containsKey(x)) {
                IpFilterRule4V ipFilterRule4V = new IpFilterRule4V();
                ipFilterRule4V.white();
                ipFilterRule4V.setId(UUIDSimple.id());
                ipFilterRule4V.setIp(x);
                whiteMap.put(x, ipFilterRule4V);
                storeList.add(ipFilterRule4V);
            }
        });

        if (storeList.size() > 0) {
            dbWrapper.insertBatch(storeList);
            logger.info("add new ip filter rule:" + storeList);
        }

        ipChecker.loadRule(blackMap, whiteMap);
    }

    @Override
    public String toString() {
        return "ServerConfig{" +
                "adminPort=" + adminPort +
                ", bindIp='" + bindIp + '\'' +
                '}';
    }


}
