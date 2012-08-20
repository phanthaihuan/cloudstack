// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
// 
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.consoleproxy;

import java.util.Map;

import javax.ejb.Local;
import javax.naming.ConfigurationException;

import org.apache.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.AgentControlAnswer;
import com.cloud.agent.api.ConsoleAccessAuthenticationAnswer;
import com.cloud.agent.api.ConsoleAccessAuthenticationCommand;
import com.cloud.agent.api.ConsoleProxyLoadReportCommand;
import com.cloud.agent.api.GetVncPortAnswer;
import com.cloud.agent.api.GetVncPortCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupProxyCommand;
import com.cloud.agent.api.StopAnswer;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.manager.Commands;
import com.cloud.configuration.dao.ConfigurationDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.info.ConsoleProxyInfo;
import com.cloud.network.Network;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.component.ComponentLocator;
import com.cloud.utils.component.Inject;
import com.cloud.vm.ConsoleProxyVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineGuru;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VirtualMachineName;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.ConsoleProxyDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@Local(value = { ConsoleProxyManager.class })
public class AgentBasedConsoleProxyManager implements ConsoleProxyManager, VirtualMachineGuru<ConsoleProxyVO>, AgentHook {
    private static final Logger s_logger = Logger.getLogger(AgentBasedConsoleProxyManager.class);

    private String _name;
    @Inject
    protected HostDao _hostDao;
    @Inject
    protected UserVmDao _userVmDao;
    private String _instance;
    protected String _consoleProxyUrlDomain;
    @Inject
    private VMInstanceDao _instanceDao;
    private ConsoleProxyListener _listener;
    protected int _consoleProxyUrlPort = ConsoleProxyManager.DEFAULT_PROXY_URL_PORT;
    protected int _consoleProxyPort = ConsoleProxyManager.DEFAULT_PROXY_VNC_PORT;
    protected boolean _sslEnabled = false;
    @Inject
    AgentManager _agentMgr;
    @Inject
    VirtualMachineManager _itMgr;
    @Inject
    protected ConsoleProxyDao _cpDao;
    public int getVncPort(VMInstanceVO vm) {
        if (vm.getHostId() == null) {
            return -1;
        }
        GetVncPortAnswer answer = (GetVncPortAnswer) _agentMgr.easySend(vm.getHostId(), new GetVncPortCommand(vm.getId(), vm.getHostName()));
        return (answer == null || !answer.getResult()) ? -1 : answer.getPort();
    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {

        if (s_logger.isInfoEnabled()) {
            s_logger.info("Start configuring AgentBasedConsoleProxyManager");
        }

        _name = name;

        ComponentLocator locator = ComponentLocator.getCurrentLocator();
        ConfigurationDao configDao = locator.getDao(ConfigurationDao.class);
        if (configDao == null) {
            throw new ConfigurationException("Unable to get the configuration dao.");
        }

        Map<String, String> configs = configDao.getConfiguration("management-server", params);
        String value = configs.get("consoleproxy.url.port");
        if (value != null) {
            _consoleProxyUrlPort = NumbersUtil.parseInt(value, ConsoleProxyManager.DEFAULT_PROXY_URL_PORT);
        }
        
        value = configs.get("consoleproxy.port");
        if (value != null) {
            _consoleProxyPort = NumbersUtil.parseInt(value, ConsoleProxyManager.DEFAULT_PROXY_VNC_PORT);
        }

        value = configs.get("consoleproxy.sslEnabled");
        if (value != null && value.equalsIgnoreCase("true")) {
            _sslEnabled = true;
        }

        _instance = configs.get("instance.name");

        _consoleProxyUrlDomain = configs.get("consoleproxy.url.domain");
        
        _listener = new ConsoleProxyListener(this);
        _agentMgr.registerForHostEvents(_listener, true, true, false);
        
        _itMgr.registerGuru(VirtualMachine.Type.ConsoleProxy, this);

        if (s_logger.isInfoEnabled()) {
            s_logger.info("AgentBasedConsoleProxyManager has been configured. SSL enabled: " + _sslEnabled);
        }
        return true;
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    HostVO findHost(VMInstanceVO vm) {
        return _hostDao.findById(vm.getHostId());
    }

    @Override
    public ConsoleProxyInfo assignProxy(long dataCenterId, long userVmId) {
        UserVmVO userVm = _userVmDao.findById(userVmId);
        if (userVm == null) {
            s_logger.warn("User VM " + userVmId + " no longer exists, return a null proxy for user vm:" + userVmId);
            return null;
        }

        HostVO host = findHost(userVm);
        if (host != null) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Assign embedded console proxy running at " + host.getName() + " to user vm " + userVmId + " with public IP "
                        + host.getPublicIpAddress());
            }

            // only private IP, public IP, host id have meaningful values, rest
            // of all are place-holder values
            String publicIp = host.getPublicIpAddress();
            if (publicIp == null) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Host " + host.getName() + "/" + host.getPrivateIpAddress()
                            + " does not have public interface, we will return its private IP for cosole proxy.");
                }
                publicIp = host.getPrivateIpAddress();
            }
            
            int urlPort = _consoleProxyUrlPort;

            if (host.getProxyPort() != null && host.getProxyPort().intValue() > 0) {
                urlPort = host.getProxyPort().intValue();
            }
            
            return new ConsoleProxyInfo(_sslEnabled, publicIp, _consoleProxyPort, urlPort, _consoleProxyUrlDomain);
        } else {
            s_logger.warn("Host that VM is running is no longer available, console access to VM " + userVmId + " will be temporarily unavailable.");
        }
        return null;
    }
    
    @Override
    public void onLoadReport(ConsoleProxyLoadReportCommand cmd) {
    }

    @Override
    public AgentControlAnswer onConsoleAccessAuthentication(ConsoleAccessAuthenticationCommand cmd) {
        long vmId = 0;

        if (cmd.getVmId() != null && cmd.getVmId().isEmpty()) {
            if (s_logger.isTraceEnabled()) {
                s_logger.trace("Invalid vm id sent from proxy(happens when proxy session has terminated)");
            }
            return new ConsoleAccessAuthenticationAnswer(cmd, false);
        }

        try {
            vmId = Long.parseLong(cmd.getVmId());
        } catch (NumberFormatException e) {
            s_logger.error("Invalid vm id " + cmd.getVmId() + " sent from console access authentication", e);
            return new ConsoleAccessAuthenticationAnswer(cmd, false);
        }

        // TODO authentication channel between console proxy VM and management
        // server needs to be secured,
        // the data is now being sent through private network, but this is
        // apparently not enough
        VMInstanceVO vm = _instanceDao.findById(vmId);
        if (vm == null) {
            return new ConsoleAccessAuthenticationAnswer(cmd, false);
        }

        if (vm.getHostId() == null) {
            s_logger.warn("VM " + vmId + " lost host info, failed authentication request");
            return new ConsoleAccessAuthenticationAnswer(cmd, false);
        }

        HostVO host = _hostDao.findById(vm.getHostId());
        if (host == null) {
            s_logger.warn("VM " + vmId + "'s host does not exist, fail authentication request");
            return new ConsoleAccessAuthenticationAnswer(cmd, false);
        }

        String sid = cmd.getSid();
        if (sid == null || !sid.equals(vm.getVncPassword())) {
            s_logger.warn("sid " + sid + " in url does not match stored sid " + vm.getVncPassword());
            return new ConsoleAccessAuthenticationAnswer(cmd, false);
        }

        return new ConsoleAccessAuthenticationAnswer(cmd, true);
    }

    @Override
    public void onAgentConnect(HostVO host, StartupCommand cmd) {
    }

    @Override
    public void onAgentDisconnect(long agentId, Status state) {
    }

    @Override
    public ConsoleProxyVO startProxy(long proxyVmId) {
        return null;
    }

    @Override
    public boolean destroyProxy(long proxyVmId) {
        return false;
    }

    @Override
    public boolean rebootProxy(long proxyVmId) {
        return false;
    }

    @Override
    public boolean stopProxy(long proxyVmId) {
        return false;
    }

    @Override
    public void setManagementState(ConsoleProxyManagementState state) {
    }
    
    @Override
    public ConsoleProxyManagementState getManagementState() {
    	return null;
    }
    
    @Override
    public void resumeLastManagementState() {
    }
    
    @Override
    public void startAgentHttpHandlerInVM(StartupProxyCommand startupCmd) {
    }

    @Override
    public String getName() {
        return _name;
    }

    @Override
    public Long convertToId(String vmName) {
        if (!VirtualMachineName.isValidConsoleProxyName(vmName, _instance)) {
            return null;
        }
        return VirtualMachineName.getConsoleProxyId(vmName);
    }
    
    @Override
    public ConsoleProxyVO findByName(String name) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ConsoleProxyVO findById(long id) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ConsoleProxyVO persist(ConsoleProxyVO vm) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean finalizeVirtualMachineProfile(VirtualMachineProfile<ConsoleProxyVO> profile, DeployDestination dest, ReservationContext context) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean finalizeDeployment(Commands cmds, VirtualMachineProfile<ConsoleProxyVO> profile, DeployDestination dest, ReservationContext context) {
        // TODO Auto-generated method stub
        return false;
    }
    
    @Override
    public boolean finalizeCommandsOnStart(Commands cmds, VirtualMachineProfile<ConsoleProxyVO> profile) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean finalizeStart(VirtualMachineProfile<ConsoleProxyVO> profile, long hostId, Commands cmds, ReservationContext context) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void finalizeStop(VirtualMachineProfile<ConsoleProxyVO> profile, StopAnswer answer) {
        // TODO Auto-generated method stub
    }
    
    @Override 
    public void finalizeExpunge(ConsoleProxyVO proxy) {
    }

    @Override
    public boolean plugNic(Network network, NicTO nic, VirtualMachineTO vm,
            ReservationContext context, DeployDestination dest) throws ConcurrentOperationException, ResourceUnavailableException,
            InsufficientCapacityException {
        //not supported
        throw new UnsupportedOperationException("Plug nic is not supported for vm of type " + vm.getType());
    }


    @Override
    public boolean unplugNic(Network network, NicTO nic, VirtualMachineTO vm,
            ReservationContext context, DeployDestination dest) throws ConcurrentOperationException, ResourceUnavailableException {
        //not supported
        throw new UnsupportedOperationException("Unplug nic is not supported for vm of type " + vm.getType());
    }
    
    @Override 
    public void prepareStop(VirtualMachineProfile<ConsoleProxyVO> profile) {
    }
}
