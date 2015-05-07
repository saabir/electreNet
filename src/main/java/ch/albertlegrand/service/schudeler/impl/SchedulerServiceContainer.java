package ch.albertlegrand.service.schudeler.impl;

import ch.albertlegrand.business.utility.exceptions.ErrorCodeException;
import ch.albertlegrand.business.utility.exceptions.SCHEDULER_PROBLEM;
import ch.albertlegrand.data.dao.DaoSchedulerService;
import ch.albertlegrand.service.schudeler.SchedulerService;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: stefanasemota
 * Date: 17.08.11
 * Time: 13:26
 * To change this template use File | Settings | File Templates.
 */
public class SchedulerServiceContainer implements ApplicationContextAware, InitializingBean, DisposableBean {

    protected Logger logger = Logger.getLogger(SchedulerServiceContainer.class);

    protected DaoSchedulerService schedulerServiceDao;

    /**
     * Temp, initial list of SchedulerServices, it will be set null after this service init is completed.
     */
    protected List<SchedulerService> initSchedulerServices;

    /**
     * Actual storage of scheduler services in this container. Key = Unique name per SchedulerService (scheduler name).
     */
    protected Map<String, SchedulerService> schedulerServiceMap = new HashMap<String, SchedulerService>();

    public void setSchedulerServiceDao(DaoSchedulerService schedulerServiceDao) {
        this.schedulerServiceDao = schedulerServiceDao;
    }

    public List<String> getSchedulerServiceNames() {
        return new ArrayList<String>(schedulerServiceMap.keySet());
    }

    public List<String> getInitializedSchedulerServiceNames() {
        ArrayList<String> result = new ArrayList<String>();
        ArrayList<String> names = new ArrayList<String>(schedulerServiceMap.keySet());
        for (String name : names) {
            SchedulerService sservice = getSchedulerService(name);
            if (sservice.isInit())
                result.add(name);
        }
        return result;
    }

    public boolean hasSchedulerService(String schedulerServiceName) {
        return schedulerServiceMap.containsKey(schedulerServiceName);
    }

    public SchedulerService getSchedulerService(String schedulerServiceName) {
        if (!schedulerServiceMap.containsKey(schedulerServiceName)) {
            throw new ErrorCodeException(SCHEDULER_PROBLEM.ErrorCode.SCHEDULER_SERVICE_NOT_FOUND, "SchedulerService " + schedulerServiceName + " not found in repository.");
        }
        return schedulerServiceMap.get(schedulerServiceName);
    }

    public void addAndInitSchedulerService(SchedulerService schedulerService) {
        // Quartz oddity: we can not obtain scheduler name without first initialize it first!
        synchronized (this) {
            String schedulerServiceName = null;
            try {
                // Init scheduler now.
                schedulerService.init();
                schedulerServiceName = schedulerService.getName();

                // Ensure we do not crash with other existing scheduler name.
                if (hasSchedulerService(schedulerServiceName)) {
                    schedulerService.destroy();
                    throw new ErrorCodeException(SCHEDULER_PROBLEM.ErrorCode.SCHEDULER_PROBLEM, "SchedulerService " + schedulerServiceName +
                            " has been init and shutdown because it already exists in this application container.");
                }
            } catch (ErrorCodeException e) {
                // If service has already init, let's try to get the name from config instead
                if (schedulerService.isInit() && schedulerServiceName == null) {
                    schedulerServiceName = schedulerService.getConfigSchedulerName();
                } else {
                    // re throw it.
                    throw e;
                }
            }

            // Ensure we have a name.
            if (schedulerServiceName == null) {
                schedulerServiceName = "FAILED_INIT_" + UUID.randomUUID().toString();
                logger.warn("SchedulerService has been initailzed, but failed to obtain a name. Assigned random unique name: " + schedulerServiceName);
            }

            // Now save it in our cache map.
            schedulerServiceMap.put(schedulerServiceName, schedulerService);
            logger.log(Level.DEBUG, "SchedulerService " + schedulerServiceName + " is initialized and added to container.");
        }
    }

    public void removeAndDestroySchedulerService(String schedulerServiceName) {
        synchronized (this) {
            SchedulerService schedulerService = getSchedulerService(schedulerServiceName);
            if (schedulerService.isInit()) {
                schedulerService.destroy();
            }
            schedulerServiceMap.remove(schedulerServiceName);
            logger.log(Level.INFO, "SchedulerService " + schedulerServiceName + " is removed from container.");
        }
    }

    protected void loadStoredSchedulerServices() {
        logger.info("Loading scheduler services from stored config props.");
        List<String> names = schedulerServiceDao.getSchedulerServiceNames();
        int initCount = 0;
        for (String name : names) {
            SchedulerService schedulerService = schedulerServiceDao.getSchedulerService(name);
            try {
                addAndInitSchedulerService(schedulerService);
                initCount++;
            } catch (Exception e) {
                logger.log(Level.ERROR, "Failed to initialize SchedulerService " + name, e);
            }
        }
        logger.log(Level.INFO, initCount + "/" + names.size() + " scheduler services initialized from stored config props.");
    }

    public void init() {
        // Load and init all stored scheduler services first.
        loadStoredSchedulerServices();

        // Now initialize all pre-configured scheduler services
        int initCount = 0;
        for (SchedulerService schedulerService : initSchedulerServices) {
            try {
                addAndInitSchedulerService(schedulerService);
                initCount++;
            } catch (Exception e) {
                logger.log(Level.ERROR, "Failed to initialize service " + schedulerService, e);
            }
        }
        logger.log(Level.INFO, initCount + "/" + initSchedulerServices.size() + " pre-configured SchedulerService has been intialized.");
    }

    @Override
    public void destroy() {
        // Destroying services and remove from the container map.
        int destroyCount = 0;
        int totalCount = schedulerServiceMap.size();
        Iterator<Map.Entry<String, SchedulerService>> entryIterator = schedulerServiceMap.entrySet().iterator();
        while (entryIterator.hasNext()) {
            Map.Entry<String, SchedulerService> schedulerServiceEntry = entryIterator.next();
            String schedulerServiceName = schedulerServiceEntry.getKey();
            SchedulerService schedulerService = schedulerServiceEntry.getValue();
            try {
                schedulerService.destroy();
                entryIterator.remove();
                destroyCount++;
                logger.info("SchedulerService " + schedulerServiceName + " is destroyed and removed from container.");
            } catch (Exception e) {
                logger.error("Failed to destroy service " + schedulerServiceName, e);
            }
        }
        logger.log(Level.INFO, destroyCount + "/" + totalCount + " SchedulerService has been destroyed.");
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        init();
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        Map<String, SchedulerService> serviceBeans = applicationContext.getBeansOfType(SchedulerService.class);
        initSchedulerServices = new ArrayList<SchedulerService>(serviceBeans.values());
        logger.log(Level.DEBUG, "Spring context initialized, detected " + initSchedulerServices.size() + " pre-configured SchedulerService instances.");
    }

}
