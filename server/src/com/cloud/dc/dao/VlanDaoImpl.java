/**
 *  Copyright (C) 2010 Cloud.com, Inc.  All rights reserved.
 * 
 * This software is licensed under the GNU General Public License v3 or later.
 * 
 * It is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */

package com.cloud.dc.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.ejb.Local;
import javax.naming.ConfigurationException;

import com.cloud.dc.AccountVlanMapVO;
import com.cloud.dc.PodVlanMapVO;
import com.cloud.dc.Vlan;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.VlanVO;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.utils.Pair;
import com.cloud.utils.component.ComponentLocator;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;

@Local(value={VlanDao.class})
public class VlanDaoImpl extends GenericDaoBase<VlanVO, Long> implements VlanDao {
    
	private final String FindZoneWideVlans = "SELECT * FROM vlan WHERE data_center_id=? and vlan_type=? and vlan_id!=? and id not in (select vlan_db_id from account_vlan_map)";
	
	protected SearchBuilder<VlanVO> ZoneVlanIdSearch;
	protected SearchBuilder<VlanVO> ZoneSearch;
	protected SearchBuilder<VlanVO> ZoneTypeSearch;
	protected SearchBuilder<VlanVO> ZoneTypeAllPodsSearch;
	protected SearchBuilder<VlanVO> ZoneTypePodSearch;
	protected SearchBuilder<VlanVO> ZoneVlanSearch;
	protected SearchBuilder<VlanVO> NetworkVlanSearch;

	protected PodVlanMapDaoImpl _podVlanMapDao = new PodVlanMapDaoImpl();
	protected AccountVlanMapDao _accountVlanMapDao = new AccountVlanMapDaoImpl();
	protected IPAddressDao _ipAddressDao = null;
	 	
    @Override
    public VlanVO findByZoneAndVlanId(long zoneId, String vlanId) {
    	SearchCriteria<VlanVO> sc = ZoneVlanIdSearch.create();
    	sc.setParameters("zoneId", zoneId);
    	sc.setParameters("vlanId", vlanId);
        return findOneBy(sc);
    }
    
    @Override
    public List<VlanVO> listByZone(long zoneId) {
    	SearchCriteria<VlanVO> sc = ZoneSearch.create();
    	sc.setParameters("zoneId", zoneId);
    	return listBy(sc);
    }
	
    public VlanDaoImpl() {
    	ZoneVlanIdSearch = createSearchBuilder();
    	ZoneVlanIdSearch.and("zoneId", ZoneVlanIdSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        ZoneVlanIdSearch.and("vlanId", ZoneVlanIdSearch.entity().getVlanTag(), SearchCriteria.Op.EQ);
        ZoneVlanIdSearch.done();
        
        ZoneSearch = createSearchBuilder();
        ZoneSearch.and("zoneId", ZoneSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        ZoneSearch.done();
        
        ZoneTypeSearch = createSearchBuilder();
        ZoneTypeSearch.and("zoneId", ZoneTypeSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        ZoneTypeSearch.and("vlanType", ZoneTypeSearch.entity().getVlanType(), SearchCriteria.Op.EQ);
        ZoneTypeSearch.done();
        
        NetworkVlanSearch = createSearchBuilder();
        NetworkVlanSearch.and("networkOfferingId", NetworkVlanSearch.entity().getNetworkId(), SearchCriteria.Op.EQ);
        NetworkVlanSearch.done();
    }

    @Override
    public List<VlanVO> listZoneWideVlans(long zoneId, VlanType vlanType, String vlanId){
    	SearchCriteria<VlanVO> sc = ZoneVlanSearch.create();
    	sc.setParameters("zoneId", zoneId);
    	sc.setParameters("vlanId", vlanId);
    	sc.setParameters("vlanType", vlanType);
    	return listBy(sc);
    }
    
	@Override
	public List<VlanVO> listByZoneAndType(long zoneId, VlanType vlanType) {
		SearchCriteria<VlanVO> sc = ZoneTypeSearch.create();
    	sc.setParameters("zoneId", zoneId);
    	sc.setParameters("vlanType", vlanType);
        return listBy(sc);
	}
	
	
	@Override
    public List<VlanVO> listByType(VlanType vlanType) {
        SearchCriteria<VlanVO> sc = ZoneTypeSearch.create();
        sc.setParameters("vlanType", vlanType);
        return listBy(sc);
    }

	@Override
	public List<VlanVO> listVlansForPod(long podId) {
		//FIXME: use a join statement to improve the performance (should be minor since we expect only one or two
		List<PodVlanMapVO> vlanMaps = _podVlanMapDao.listPodVlanMapsByPod(podId);
		List<VlanVO> result  = new ArrayList<VlanVO>();
		for (PodVlanMapVO pvmvo: vlanMaps) {
			result.add(findById(pvmvo.getVlanDbId()));
		}
		return result;
	}

	@Override
	public List<VlanVO> listVlansForPodByType(long podId, VlanType vlanType) {
		//FIXME: use a join statement to improve the performance (should be minor since we expect only one or two)
		List<PodVlanMapVO> vlanMaps = _podVlanMapDao.listPodVlanMapsByPod(podId);
		List<VlanVO> result  = new ArrayList<VlanVO>();
		for (PodVlanMapVO pvmvo: vlanMaps) {
			VlanVO vlan =findById(pvmvo.getVlanDbId());
			if (vlan.getVlanType() == vlanType) {
				result.add(vlan);
			}
		}
		return result;
	}
	
	@Override
	public List<VlanVO> listVlansForAccountByType(Long zoneId, long accountId, VlanType vlanType) {
		//FIXME: use a join statement to improve the performance (should be minor since we expect only one or two)
		List<AccountVlanMapVO> vlanMaps = _accountVlanMapDao.listAccountVlanMapsByAccount(accountId);
		List<VlanVO> result  = new ArrayList<VlanVO>();
		for (AccountVlanMapVO acvmvo: vlanMaps) {
			VlanVO vlan =findById(acvmvo.getVlanDbId());
			if (vlan.getVlanType() == vlanType && (zoneId == null || vlan.getDataCenterId() == zoneId)) {
				result.add(vlan);
			}
		}
		return result;
	}

	@Override
	public void addToPod(long podId, long vlanDbId) {
		PodVlanMapVO pvmvo = new PodVlanMapVO(podId, vlanDbId);
		_podVlanMapDao.persist(pvmvo);
		
	}

	@Override
	public boolean configure(String name, Map<String, Object> params)
			throws ConfigurationException {
		boolean result = super.configure(name, params);
		if (result) {
	        final ComponentLocator locator = ComponentLocator.getCurrentLocator();
			_ipAddressDao = locator.getDao(IPAddressDao.class);
			if (_ipAddressDao == null) {
				throw new ConfigurationException("Unable to get " + IPAddressDao.class.getName());
			}
		}
        ZoneTypeAllPodsSearch = createSearchBuilder();
        ZoneTypeAllPodsSearch.and("zoneId", ZoneTypeAllPodsSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        ZoneTypeAllPodsSearch.and("vlanType", ZoneTypeAllPodsSearch.entity().getVlanType(), SearchCriteria.Op.EQ);
        
        SearchBuilder<PodVlanMapVO> PodVlanSearch = _podVlanMapDao.createSearchBuilder();
        PodVlanSearch.and("podId", PodVlanSearch.entity().getPodId(), SearchCriteria.Op.NNULL);
        ZoneTypeAllPodsSearch.join("vlan", PodVlanSearch, PodVlanSearch.entity().getVlanDbId(), ZoneTypeAllPodsSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        
        ZoneTypeAllPodsSearch.done();
        PodVlanSearch.done();
        
        ZoneTypePodSearch = createSearchBuilder();
        ZoneTypePodSearch.and("zoneId", ZoneTypePodSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        ZoneTypePodSearch.and("vlanType", ZoneTypePodSearch.entity().getVlanType(), SearchCriteria.Op.EQ);
        
        SearchBuilder<PodVlanMapVO> PodVlanSearch2 = _podVlanMapDao.createSearchBuilder();
        PodVlanSearch2.and("podId", PodVlanSearch2.entity().getPodId(), SearchCriteria.Op.EQ);
        ZoneTypePodSearch.join("vlan", PodVlanSearch2,  PodVlanSearch2.entity().getVlanDbId(), ZoneTypePodSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        PodVlanSearch2.done();
        ZoneTypePodSearch.done();

		return result;
	}
	
	private VlanVO findNextVlan(long zoneId, Vlan.VlanType vlanType) {
		List<VlanVO> allVlans = listByZoneAndType(zoneId, vlanType);
		List<VlanVO> emptyVlans = new ArrayList<VlanVO>();
		List<VlanVO> fullVlans = new ArrayList<VlanVO>();
		
		// Try to find a VLAN that is partially allocated
		for (VlanVO vlan : allVlans) {
			long vlanDbId = vlan.getId();
			
			int countOfAllocatedIps = _ipAddressDao.countIPs(zoneId, vlanDbId, true);
			int countOfAllIps = _ipAddressDao.countIPs(zoneId, vlanDbId, false);
			
			if ((countOfAllocatedIps > 0) && (countOfAllocatedIps < countOfAllIps)) {
				return vlan;
			} else if (countOfAllocatedIps == 0) {
				emptyVlans.add(vlan);
			} else if (countOfAllocatedIps == countOfAllIps) {
				fullVlans.add(vlan);
			}
		}
		
		if (emptyVlans.isEmpty()) {
			return null;
		}
		
		// Try to find an empty VLAN with the same tag/subnet as a VLAN that is full
		for (VlanVO fullVlan : fullVlans) {
			for (VlanVO emptyVlan : emptyVlans) {
				if (fullVlan.getVlanTag().equals(emptyVlan.getVlanTag()) && 
					fullVlan.getVlanGateway().equals(emptyVlan.getVlanGateway()) &&
					fullVlan.getVlanNetmask().equals(emptyVlan.getVlanNetmask())) {
					return emptyVlan;
				}
			}
		}
		
		// Return a random empty VLAN
		return emptyVlans.get(0);
	}

	@Override
	public boolean zoneHasDirectAttachUntaggedVlans(long zoneId) {
		SearchCriteria<VlanVO> sc = ZoneTypeAllPodsSearch.create();
    	sc.setParameters("zoneId", zoneId);
    	sc.setParameters("vlanType", VlanType.DirectAttached);
    	
        return listIncludingRemovedBy(sc).size() > 0;
	}


	public Pair<String, VlanVO> assignPodDirectAttachIpAddress(long zoneId,
			long podId, long accountId, long domainId) {
		SearchCriteria<VlanVO> sc = ZoneTypePodSearch.create();
    	sc.setParameters("zoneId", zoneId);
    	sc.setParameters("vlanType", VlanType.DirectAttached);
    	sc.setJoinParameters("vlan", "podId", podId);
    	
    	VlanVO vlan = findOneIncludingRemovedBy(sc);
    	if (vlan == null) {
    		return null;
    	}
    	
    	return null;
//    	String ipAddress = _ipAddressDao.assignIpAddress(accountId, domainId, vlan.getId(), false).getAddress();
//    	if (ipAddress == null) {
//    		return null;
//    	}
//		return new Pair<String, VlanVO>(ipAddress, vlan);

	}
	
	@Override
	@DB
	public List<VlanVO> searchForZoneWideVlans(long dcId, String vlanType, String vlanId){
		
	    StringBuilder sql = new StringBuilder(FindZoneWideVlans);

	    Transaction txn = Transaction.currentTxn();
		PreparedStatement pstmt = null;
	    try {
	        pstmt = txn.prepareAutoCloseStatement(sql.toString());
	        pstmt.setLong(1, dcId);
	        pstmt.setString(2, vlanType);
	        pstmt.setString(3, vlanId);
	        
	        ResultSet rs = pstmt.executeQuery();
	        List<VlanVO> zoneWideVlans = new ArrayList<VlanVO>();

	        while (rs.next()) {
	        	zoneWideVlans.add(toEntityBean(rs, false));
	        }
	        
	        return zoneWideVlans;
	    } catch (SQLException e) {
	        throw new CloudRuntimeException("Unable to execute " + pstmt.toString(), e);
	    }
	}
    
	@Override
    public List<VlanVO> listVlansByNetworkId(long networkOfferingId) {
       SearchCriteria<VlanVO> sc = NetworkVlanSearch.create();
        sc.setParameters("networkOfferingId", networkOfferingId);
        return listBy(sc);
    }
	
}
