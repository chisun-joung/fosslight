/*
 * Copyright (c) 2021 LG Electronics Inc.
 * SPDX-License-Identifier: AGPL-3.0-only 
 */

package oss.fosslight.service.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.beanutils.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import oss.fosslight.CoTopComponent;
import oss.fosslight.common.CoCodeManager;
import oss.fosslight.common.CoConstDef;
import oss.fosslight.common.CommonFunction;
import oss.fosslight.common.T2CoValidationConfig;
import oss.fosslight.domain.CoMail;
import oss.fosslight.domain.CoMailManager;
import oss.fosslight.domain.CommentsHistory;
import oss.fosslight.domain.History;
import oss.fosslight.domain.LicenseMaster;
import oss.fosslight.domain.OssAnalysis;
import oss.fosslight.domain.OssLicense;
import oss.fosslight.domain.OssMaster;
import oss.fosslight.domain.Project;
import oss.fosslight.domain.ProjectIdentification;
import oss.fosslight.domain.T2File;
import oss.fosslight.domain.T2Users;
import oss.fosslight.domain.Vulnerability;
import oss.fosslight.repository.FileMapper;
import oss.fosslight.repository.OssMapper;
import oss.fosslight.repository.ProjectMapper;
import oss.fosslight.repository.T2UserMapper;
import oss.fosslight.service.CommentService;
import oss.fosslight.service.HistoryService;
import oss.fosslight.service.OssService;
import oss.fosslight.util.DateUtil;
import oss.fosslight.util.FileUtil;
import oss.fosslight.util.StringUtil;

@Service
@Slf4j
public class OssServiceImpl extends CoTopComponent implements OssService {
	// Service
	@Autowired CommentService commentService;
	@Autowired HistoryService historyService;
	
	// Mapper
	@Autowired OssMapper ossMapper;
	@Autowired T2UserMapper userMapper;
	@Autowired FileMapper fileMapper;
	@Autowired ProjectMapper projectMapper;
	
	@Override
	public Map<String,Object> getOssMasterList(OssMaster ossMaster) {
		// 기간 검색 조건
		if(!isEmpty(ossMaster.getcEndDate())) {
			ossMaster.setcEndDate(DateUtil.addDaysYYYYMMDD(ossMaster.getcEndDate(), 1));
		}
		
		if(!isEmpty(ossMaster.getmEndDate())) {
			ossMaster.setmEndDate(DateUtil.addDaysYYYYMMDD(ossMaster.getmEndDate(), 1));
		}
		
		if(isEmpty(ossMaster.getOssNameAllSearchFlag())) {
			ossMaster.setOssNameAllSearchFlag(CoConstDef.FLAG_NO);
		}
		
		if(isEmpty(ossMaster.getLicenseNameAllSearchFlag())) {
			ossMaster.setLicenseNameAllSearchFlag(CoConstDef.FLAG_NO);
		}
		
		HashMap<String, Object> map = new HashMap<String, Object>();
		ossMaster.setCveIdText();
		int records = ossMapper.selectOssMasterTotalCount(ossMaster);
		ossMaster.setTotListSize(records);
		
		ArrayList<OssMaster> newList = new ArrayList<>();
		List<OssMaster> list = ossMapper.selectOssList(ossMaster);
		
		if(list.size() == 0){
			ossMaster.setVersionCheck(CoConstDef.FLAG_NO);
			list = ossMapper.selectOssList(ossMaster);
		}
		
		String orgOssName = ossMaster.getOssName();
		
		for(OssMaster oss : list){
			// multi flag 가 null 인 경우
			if(isEmpty(oss.getMultiFlag())) {
				oss.setMultiFlag("1");
			}
			
			if("1".equals(oss.getMultiFlag())) {
				ossMaster.setOssId(oss.getOssId());
				ossMaster.setOssName(oss.getOssName());
				List<OssMaster> subList = ossMapper.selectOssSubList(ossMaster);
				
				newList.addAll(subList);
			} else {
				newList.add(oss);
			}
		}
		
		ossMaster.setOssName(orgOssName);
		
		// license name 처리
		if(newList != null && !newList.isEmpty()) {
			OssMaster param = new OssMaster();
			
			for(OssMaster bean : newList) {
				param.addOssIdList(bean.getOssId());
			}

			List<OssLicense> licenseList = ossMapper.selectOssLicenseList(param);
			
			for(OssLicense licenseBean : licenseList) {
				for(OssMaster bean : newList) {
					if(licenseBean.getOssId().equals(bean.getOssId())) {
						bean.addOssLicense(licenseBean);
						break;
					}
				}
			}

			for(OssMaster bean : newList) {
				if(bean.getOssLicenses() != null && !bean.getOssLicenses().isEmpty()) {
					bean.setLicenseName(CommonFunction.makeLicenseExpression(bean.getOssLicenses()));
				}
				
				// group by key 설정 grid 상에서 대소문자 구분되어 대문자로 모두 치화하여 그룹핑
				bean.setGroupKey(bean.getOssName().toUpperCase());
				
				// NICK NAME ICON 표시
				if(CoConstDef.FLAG_YES.equals(ossMaster.getSearchFlag())) {
					bean.setOssName(StringUtil.replaceHtmlEscape(bean.getOssName()));
					
					if(!isEmpty(bean.getOssNickname())) {
						bean.setOssName("<span class='iconSet nick'>Nick</span>&nbsp;" + bean.getOssName());
					} else {
						bean.setOssName("<span class='iconSet nick dummy'></span>&nbsp;" + bean.getOssName());
					}
				}
			}
		}
		
		map.put("page", ossMaster.getCurPage());
		map.put("total", ossMaster.getTotBlockSize());
		map.put("records", records);
		map.put("rows", newList);
		
		return map; 
	}
	
	@Override
	@Cacheable(value="autocompleteCache", key="#root.methodName")
	public List<OssMaster> getOssNameList() {
		return ossMapper.selectOssNameList();
	}
	
	@Override
	public String[] getOssNickNameListByOssName(String ossName) {
		List<String> nickList = new ArrayList<>();
		
		if(!isEmpty(ossName)) {
			OssMaster param = new OssMaster();
			param.setOssName(ossName);
			List<OssMaster> list =  ossMapper.selectOssNicknameList(param);
			
			if(list != null) {
				for(OssMaster bean : list) {
					if(!isEmpty(bean.getOssNickname()) && !nickList.contains(bean.getOssNickname())) {
						nickList.add(bean.getOssNickname());
					}
				}
			}
		}
		
		return nickList.toArray(new String[nickList.size()]);
	}
	
	@Override
	public Map<String, Object> getOssLicenseList(OssMaster ossMaster) {
		HashMap<String, Object> map = new HashMap<String, Object>();
		List<OssLicense> list = ossMapper.selectOssLicenseList(ossMaster);

		if(!CommonFunction.isAdmin() && list != null) {
			for(OssLicense license : list) {
				if(!isEmpty(license.getOssCopyright())) {
					license.setOssCopyright(CommonFunction.lineReplaceToBR( StringUtil.replaceHtmlEscape( license.getOssCopyright() )));
				}
			}
		}
		
		map.put("rows", list);
		
		return map;
	}
	
	@Override
	public List<Vulnerability> getOssVulnerabilityList(Vulnerability vulnParam) {
		return ossMapper.getOssVulnerabilityList(vulnParam);
	}
	
	@Override
	public OssMaster getOssMasterOne(OssMaster ossMaster) {
		
		ossMaster = ossMapper.selectOssOne(ossMaster);
		List<OssMaster> ossNicknameList = ossMapper.selectOssNicknameList(ossMaster);
		List<OssMaster> ossDownloadLocation = ossMapper.selectOssDownloadLocationList(ossMaster);
		List<OssLicense> ossLicenses = ossMapper.selectOssLicenseList(ossMaster);
		
		String totLicenseTxt = CommonFunction.makeLicenseExpression(ossLicenses);
		ossMaster.setTotLicenseTxt(totLicenseTxt);
		
		StringBuilder sb = new StringBuilder();
		
		for(OssMaster ossNickname : ossNicknameList){
			sb.append(ossNickname.getOssNickname()).append(",");
		}
		
		String[] ossNicknames = new String(sb).split("[,]");
		
		sb = new StringBuilder();
		String[] ossDownloadLocations = null;
		
		if(ossDownloadLocation.size() > 0) {
			for(OssMaster location : ossDownloadLocation) {
				sb.append(location.getDownloadLocation()).append(",");
			}
			
			ossDownloadLocations = new String(sb).split("[,]");
		} else {
			ossDownloadLocations = new String(ossMaster.getDownloadLocation()).split("[,]");
		}
		
		ossMaster.setOssNicknames(ossNicknames);
		ossMaster.setDownloadLocations(ossDownloadLocations);
		ossMaster.setOssLicenses(ossLicenses);
		
		return ossMaster;
	}
	
	@Override
	public Map<String, Object> getOssPopupList(OssMaster ossMaster) {
		HashMap<String, Object> map = new HashMap<String, Object>();
		
		ossMaster.setOssName(CoCodeManager.OSS_INFO_BY_ID.get(ossMaster.getOssId()).getOssName());
		
		int records = ossMapper.selectOssPopupTotalCount(ossMaster);
		ossMaster.setTotListSize(records);
		List<OssMaster> list = ossMapper.selectOssPopupList(ossMaster);
		
		map.put("page", ossMaster.getCurPage());
		map.put("total", ossMaster.getTotBlockSize());
		map.put("records", records);
		map.put("rows", list);
		
		return map;
	}
	
	@Override
	public OssMaster getOssInfo(String ossId, boolean isMailFormat) {
		return getOssInfo(ossId, null, isMailFormat);
	}
	
	@Override
	public OssMaster getOssInfo(String ossId, String ossName, boolean isMailFormat) {
		OssMaster param = new OssMaster();
		Map<String, OssMaster> map = new HashMap<String, OssMaster>();
		
		if(!isEmpty(ossId)) {
			param.addOssIdList(ossId);
			map = isMailFormat ? getBasicOssInfoListByIdOnTime(param) : getBasicOssInfoListById(param);
		}
		
		if(!isEmpty(ossName)) {
			param.setOssName(ossName);
			map = getNewestOssInfoOnTime(param);
		}
		
		if(map != null) {
			// nickname 정보 취득 
			for(OssMaster bean : map.values()) {
				param.setOssName(bean.getOssName());
				List<OssMaster> nickNameList = ossMapper.selectOssNicknameList(param);
				if(nickNameList != null && !nickNameList.isEmpty()) {
					List<String> nickNames = new ArrayList<>();
					
					for(OssMaster nickNameBean : nickNameList) {
						if(!isEmpty(nickNameBean.getOssNickname())) {
							nickNames.add(nickNameBean.getOssNickname());
						}
					}
					
					bean.setOssNicknames(nickNames.toArray(new String[nickNames.size()]));
				}
				
				if(isMailFormat) {
					bean.setLicenseName(CommonFunction.makeLicenseExpression(bean.getOssLicenses()));
					bean.setOssLicenses(CommonFunction.changeLicenseNameToShort(bean.getOssLicenses()));
					
					// code 변경
					if(!isEmpty(bean.getLicenseDiv())) {
						// multi license 표시 여부 판단을 위해서 코드표시명 변환 이전의 값이 필요함
						bean.setMultiLicenseFlag(bean.getLicenseDiv());
						bean.setLicenseDiv(CoCodeManager.getCodeString(CoConstDef.CD_LICENSE_DIV, bean.getLicenseDiv()));
					}
					
					if(!isEmpty(bean.getLicenseType())) {
						bean.setLicenseType(CoCodeManager.getCodeString(CoConstDef.CD_LICENSE_TYPE, bean.getLicenseType()));
					}
					
					if(!isEmpty(bean.getObligationType())) {
						bean.setObligation(CoCodeManager.getCodeString(CoConstDef.CD_OBLIGATION_TYPE, bean.getObligationType()));
					}
					
					// 날짜 형식
					if(!isEmpty(bean.getModifiedDate())) {
						bean.setModifiedDate(DateUtil.dateFormatConvert(bean.getModifiedDate(), DateUtil.TIMESTAMP_PATTERN, DateUtil.DATE_PATTERN_DASH));
					}
					
					if(!isEmpty(bean.getModifier())) {
						bean.setModifier(CoMailManager.getInstance().makeUserNameFormat(bean.getModifier()));
					}
					
					if(!isEmpty(bean.getCreatedDate())) {
						bean.setCreatedDate(DateUtil.dateFormatConvert(bean.getCreatedDate(), DateUtil.TIMESTAMP_PATTERN, DateUtil.DATE_PATTERN_DASH));
					}
					
					if(!isEmpty(bean.getCreator())) {
						bean.setCreator(CoMailManager.getInstance().makeUserNameFormat(bean.getCreator()));
					}

					bean.setAttribution(CommonFunction.lineReplaceToBR(bean.getAttribution()));
					bean.setSummaryDescription(CommonFunction.lineReplaceToBR(bean.getSummaryDescription()));
					bean.setCopyright(CommonFunction.lineReplaceToBR(bean.getCopyright()));
				}
				
				return bean;
			}
		}
		
		return null;
	}
	
	private Map<String, OssMaster> getBasicOssInfoListByIdOnTime(OssMaster ossMaster) {
		List<OssMaster> list = ossMapper.getBasicOssInfoListById(ossMaster);
		
		return makeBasicOssInfoMap(list, true, false);
	}
	
	private Map<String, OssMaster> makeBasicOssInfoMap(List<OssMaster> list, boolean useId, boolean useUpperKey) {
		Map<String, OssMaster> map = new HashMap<>();
		
		for(OssMaster bean : list) {
			OssMaster targetBean = null;
			String key = useId ? bean.getOssId() : bean.getOssName() +"_"+ avoidNull(bean.getOssVersion());
			
			if(useUpperKey) {
				key = key.toUpperCase();
			}
			
			if(map.containsKey(key)) {
				targetBean = map.get(key);
			} else {
				targetBean = bean;
			}
			
			OssLicense subBean = new OssLicense();
			subBean.setOssId(bean.getOssId());
			subBean.setLicenseId(bean.getLicenseId());
			subBean.setLicenseName(bean.getLicenseName());
			subBean.setLicenseType(bean.getLicenseType());
			subBean.setOssLicenseIdx(bean.getOssLicenseIdx());
			subBean.setOssLicenseComb(bean.getOssLicenseComb());
			subBean.setOssLicenseText(bean.getOssLicenseText());
			subBean.setOssCopyright(bean.getOssCopyright());
			
			// oss의 license type을 license의 license type 적용 이후에 set
			bean.setLicenseType(bean.getOssLicenseType());
			
			targetBean.addOssLicense(subBean);
			
			if(map.containsKey(key)) {
				map.replace(key, targetBean);
			} else {
				map.put(key, targetBean);
			}
		}
		
		return map;
	}
	
	@Override
	public History work(Object param) {
		History h = new History();
		OssMaster vo = (OssMaster) param;
		OssMaster data = getOssMasterOne(vo);
		data.setComment(vo.getComment());
		
		h.sethKey(vo.getOssId());
		h.sethTitle(vo.getOssName());
		h.sethType(CoConstDef.EVENT_CODE_OSS);
		h.setModifier(vo.getLoginUserName());
		h.setModifiedDate(vo.getCreatedDate());
		h.sethComment("");
		h.sethData(data);
		
		return h;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	@Transactional
	public void deleteOssWithVersionMerege(OssMaster ossMaster) throws IllegalAccessException, InstantiationException, InvocationTargetException, NoSuchMethodException {
		String chagedOssName = CoCodeManager.OSS_INFO_BY_ID.get(ossMaster.getNewOssId()).getOssName();
		String beforOssName = CoCodeManager.OSS_INFO_BY_ID.get(ossMaster.getOssId()).getOssName();

		// 동일한 oss에서 이동하는 경우, nick name을 별도로 등록하지 않음
		OssMaster beforeBean = getOssInfo(ossMaster.getOssId(), false);
		
		// 삭제대상 OSS 목록 취득
		Map<String, Object> rowMap = ossMergeCheckList(ossMaster);
		List<OssMaster> rowList = (List<OssMaster>) rowMap.get("rows");
		
		List<Map<String, OssMaster>> mailBeanList = new ArrayList<>();
		
		// 메일 발송을 위한 data 취득( 메일 형식을 위해 다시 DB Select )
		for(OssMaster bean : rowList) {
			if(!isEmpty(bean.getMergeStr())) {
				// 실제로 삭제처리는 중복되는 OSS Version만
				if("Duplicated".equalsIgnoreCase(bean.getMergeStr())) {
					// mail 발송을 위해 삭제전 data 취득
					Map<String, OssMaster> mailDiffMap = new HashMap<>();
					OssMaster ossMailInfo1 = getOssInfo(bean.getDelOssId(), true);
					OssMaster tempBean1 = (OssMaster) BeanUtils.cloneBean(ossMailInfo1);
					
					if(tempBean1.getOssNicknames() != null) {
						tempBean1.setOssNickname(CommonFunction.arrayToString(tempBean1.getOssNicknames(), "<br>"));
					}
					
					tempBean1.setOssId(bean.getDelOssId());
					mailDiffMap.put("before", tempBean1);
					
					OssMaster ossMailInfo2 = getOssInfo(bean.getOssId(), true);
					OssMaster tempBean2 = (OssMaster) BeanUtils.cloneBean(ossMailInfo2);
					
					if(tempBean2.getOssNicknames() != null) {
						tempBean2.setOssNickname(CommonFunction.arrayToString(tempBean2.getOssNicknames(), "<br>"));
					}
					
					tempBean2.setOssName(chagedOssName);
					mailDiffMap.put("after", tempBean2);
					
					mailBeanList.add(mailDiffMap);
				} else {
					// 실제로 삭제되는 것은 아님
					// 이름만 변경해서 비교 메일 발송
					Map<String, OssMaster> mailDiffMap = new HashMap<>();
					OssMaster ossMailInfo1 = getOssInfo(bean.getOssId(), true);
					OssMaster tempBean1 = (OssMaster) BeanUtils.cloneBean(ossMailInfo1);
					
					if(tempBean1.getOssNicknames() != null) {
						tempBean1.setOssNickname(CommonFunction.arrayToString(tempBean1.getOssNicknames(), "<br>"));
					}
					
					mailDiffMap.put("before", tempBean1);

					OssMaster tempBean2 = (OssMaster) BeanUtils.cloneBean(ossMailInfo1);
					
					if(tempBean2.getOssNicknames() != null) {
						tempBean2.setOssNickname(CommonFunction.arrayToString(tempBean2.getOssNicknames(), "<br>"));
					}
					
					tempBean2.setOssName(chagedOssName);
					mailDiffMap.put("after", tempBean2);
					
					mailBeanList.add(mailDiffMap);
				}
			}
		}

		// 삭제 처리
		for(OssMaster bean : rowList) {
			if(!isEmpty(bean.getMergeStr())) {
				boolean isDel = false;
				
				// 신규 version의 등록이 필요한 경우
				if("Added".equalsIgnoreCase(bean.getMergeStr())) {
					CommentsHistory historyBean = new CommentsHistory();
					historyBean.setReferenceDiv(CoConstDef.CD_DTL_COMMENT_OSS);
					historyBean.setReferenceId(bean.getOssId());
					historyBean.setContents("OSS 일괄 이관 처리에 의해 OSS Name이 변경되었습니다. <br/>" + "Before OSS Name : " + bean.getOssName() + "<br/>" + avoidNull(ossMaster.getComment()));
					bean.setOssName(chagedOssName);
					bean.setNewOssId(bean.getOssId()); // 삭제하지 않고 이름만 변경해서 재사용한다.
					
					ossMapper.changeOssNameByDelete(bean);
					
					commentService.registComment(historyBean);
				} else {
					// Duplicated
					bean.setNewOssId(bean.getOssId());
					bean.setOssId(bean.getDelOssId());

					CommentsHistory historyBean = new CommentsHistory();
					historyBean.setReferenceDiv(CoConstDef.CD_DTL_COMMENT_OSS);
					historyBean.setReferenceId(bean.getDelOssId());
					historyBean.setContents("OSS 일괄 이관 처리에 의해 "+bean.getOssName()+" 으로 이관되었습니다.<br/>" + avoidNull(ossMaster.getComment()));
					
					commentService.registComment(historyBean);
					
					historyBean.setReferenceId(bean.getOssId());
					historyBean.setContents("OSS 일괄 이관 처리에 의해 "+beforOssName+" 과 병합되었습니다.<br/>" + avoidNull(ossMaster.getComment()));
					
					commentService.registComment(historyBean);
					
					ossMapper.deleteOssLicense(bean);
					ossMapper.deleteOssMaster(bean);
					
					isDel = true;
				}
				
				//1. 기존 OssId를 사용중인 프로젝트의 OssId , Version 를 새로운 OssId로 교체				
				if(isDel) {
					try{
						History h = work(ossMaster);
						h.sethAction(CoConstDef.ACTION_CODE_DELETE);
						
						historyService.storeData(h);
					}catch(Exception e){
						log.error(e.getMessage(), e);
					}
				}
			}
		}
		
		OssMaster deleteNickParam = new OssMaster();
		deleteNickParam.setOssName(beforOssName);
		
		ossMapper.deleteOssNickname(deleteNickParam);
		
		// nick name merge
		// 일단 삭제된 oss name을 nickname으로 추가한다.
		OssMaster nickMergeParam  = new OssMaster();
		nickMergeParam.setOssName(chagedOssName);
		nickMergeParam.setOssNickname(beforOssName);
		ossMapper.mergeOssNickname2(nickMergeParam);
		
		if(beforeBean.getOssNicknames() != null) {
			for(String nickName : beforeBean.getOssNicknames()) {
				nickMergeParam.setOssNickname(nickName);
				
				ossMapper.mergeOssNickname2(nickMergeParam);
			}
		}
		
		CoCodeManager.getInstance().refreshOssInfo();

		for(Map<String, OssMaster> mailInfoMap : mailBeanList) {
			// 삭제대상 OSS를 사용중인 프로젝트의 목록을 코멘트로 남긴다.
			try {
				String templateComemnt = makeTemplateComment(ossMaster.getComment(), mailInfoMap.get("before"), mailInfoMap.get("after"));
				
				// 사용중인 프로젝트가 없는 경우는 코멘트를 추가작으로 남기지 않는다.
				if(!isEmpty(templateComemnt)) {
					CommentsHistory historyBean = new CommentsHistory();
					historyBean.setReferenceDiv(CoConstDef.CD_DTL_COMMENT_OSS);
					historyBean.setReferenceId(mailInfoMap.get("after").getOssId());
					historyBean.setContents(templateComemnt);
					
					commentService.registComment(historyBean);
				}
			} catch (Exception e) {
				log.error(e.getMessage(), e);
			}
			
			try {
				CoMail mailBean = new CoMail(CoConstDef.CD_MAIL_TYPE_OSS_RENAME);
				mailBean.setCompareDataBefore(mailInfoMap.get("before"));
				
				OssMaster afterOssMaster = (OssMaster) mailInfoMap.get("after");
				afterOssMaster.setModifiedDate(DateUtil.getCurrentDateTime());
				afterOssMaster.setModifier(CoMailManager.getInstance().makeUserNameFormat(loginUserName()));
				
				mailBean.setCompareDataAfter(afterOssMaster);
				mailBean.setParamOssId(mailInfoMap.get("before").getOssId());
				mailBean.setComment(ossMaster.getComment());
				
				CoMailManager.getInstance().sendMail(mailBean);
			} catch (Exception e) {
				log.error(e.getMessage(), e);
			}	
		}
	}
	
	private String makeTemplateComment(String comment, OssMaster ossMasterBefore, OssMaster ossMasterAfter) {
		Map<String, Object> convertDataMap = new HashMap<>();
		// 사용중인 프로젝트가 있는 경우
		// 메일 발송시 사용사는 쿼리와 동일
		List<Project> prjList = ossMapper.getOssChangeForUserList(ossMasterBefore);
		
		if(prjList != null && !prjList.isEmpty()) {
			for(Project prjBean : prjList) {
				prjBean.setDistributionType(CoCodeManager.getCodeString(CoConstDef.CD_DISTRIBUTION_TYPE, prjBean.getDistributionType()));
				prjBean.setCreator(makeUserNameFormatWithDivision(prjBean.getCreator()));
				prjBean.setCreatedDate(CommonFunction.formatDateSimple(prjBean.getCreatedDate()));
				prjBean.setReviewer(makeUserNameFormatWithDivision(prjBean.getReviewer()));
			}
			
			convertDataMap.put("projectList", prjList);
			convertDataMap.put("comment", comment);
			convertDataMap.put("modifierNm", makeUserNameFormat(loginUserName()));
			convertDataMap.put("ossBeforeNm", makeOssNameFormat(ossMasterBefore));
			convertDataMap.put("ossAftereNm", makeOssNameFormat(ossMasterAfter));
			convertDataMap.put("templateURL", "/template/comment/ossRenamed.html");
			
			return CommonFunction.VelocityTemplateToString(convertDataMap);
		}
		
		return null;
	}
	
	private String makeUserNameFormatWithDivision(String userId) {
		String rtnVal = "";
		T2Users userParam = new T2Users();
		userParam.setUserId(userId);
		
		T2Users userInfo = userMapper.getUser(userParam);
		
		if(userInfo != null && !isEmpty(userInfo.getUserName())) {
			if(!isEmpty(userInfo.getDivision())) {
				String _division = CoCodeManager.getCodeString(CoConstDef.CD_USER_DIVISION, userInfo.getDivision());
				
				if(!isEmpty(_division)) {
					rtnVal += _division + " ";
				}
			}
			
			rtnVal += userInfo.getUserName() + "(" + userId + ")";
		}
		
		return isEmpty(rtnVal) ? userId : rtnVal;
	}
	
	private String makeUserNameFormat(String userId) {
		String rtn = userId;
		T2Users param = new T2Users();
		param.setUserId(userId);
		T2Users userInfo = userMapper.getUser(param);
		
		if(userInfo != null) {
			rtn = avoidNull(userInfo.getUserName());
			rtn += "(" + avoidNull(userInfo.getUserId()) + ")";
		}
		
		return rtn;
	}
	
	private String makeOssNameFormat(OssMaster bean) {
		String rtnVal = "";
		
		if(bean != null) {
			rtnVal = avoidNull(bean.getOssName());
			if(!isEmpty(bean.getOssVersion())) {
				rtnVal += " (" + bean.getOssVersion() + ")";
			}
		}
		
		return rtnVal;
	}
	
	@Override
	public String[] checkNickNameRegOss(String ossName, String[] ossNicknames) {
		List<String> rtnList = new ArrayList<>();
		List<String> currntList = null;
		
		if(ossNicknames != null && ossNicknames.length > 0) {
			currntList = Arrays.asList(ossNicknames);
		}
		
		if(currntList == null) {
			currntList = new ArrayList<>();
		}
		
		if(!isEmpty(ossName)) {
			// oss name으로 등록된 nick name이 있는지 확인
			List<String> _nickNames = ossMapper.checkNickNameRegOss(ossName);
			
			if(_nickNames != null && !_nickNames.isEmpty()) {
				for(String s : _nickNames) {
					if(!isEmpty(s) && !currntList.contains(s)) {
						rtnList.add(s);
					}
				}
			}
		}
		
		return rtnList.toArray(new String[rtnList.size()]);
	}

	@Override
	public String checkExistOssConf(String ossId) {
		int resultCnt = 0;
		
		boolean projectFlag = CommonFunction.propertyFlagCheck("menu.project.use.flag", CoConstDef.FLAG_YES);
        boolean partnerFlag = CommonFunction.propertyFlagCheck("menu.partner.use.flag", CoConstDef.FLAG_YES);
        
		if(projectFlag) {
			resultCnt += ossMapper.checkExistOssConfProject(CoCodeManager.OSS_INFO_BY_ID.get(ossId));
		}
		
		if(partnerFlag) {
			resultCnt += ossMapper.checkExistOssConfPartner(CoCodeManager.OSS_INFO_BY_ID.get(ossId));
		}
		
		return Integer.toString(resultCnt);
		
	}

	@Transactional
	@Override
	@CacheEvict(value="autocompleteCache", allEntries=true)
	public String registOssMaster(OssMaster ossMaster) {
		String[] ossNicknames = ossMaster.getOssNicknames();
		String ossId = ossMaster.getOssId();
		boolean isNew = StringUtil.isEmpty(ossId);
		OssMaster orgMasterInfo = null;

		if(StringUtil.isEmpty(ossId)){
			ossMaster.setCreator(ossMaster.getLoginUserName());
		} else {
			orgMasterInfo = new OssMaster();
			orgMasterInfo.setOssId(ossId);
			orgMasterInfo = getOssMasterOne(orgMasterInfo);
		}
		
		// oss name 또는 version이 변경된 경우만 vulnerability recheck 대상으로 업데이트 한다.
		boolean vulnRecheck = false;
		
		// oss v-diff 체크 대상 선별
		// oss name이 변경된 경우 변경전 후 모두 포함해야한다.
		Map<String, OssMaster> updateOSSLicenseMap = new HashMap<>();
		
		// 변경전 oss name에 해당하는 oss_id 목록을 찾는다.
		if(!isNew) {
			OssMaster _orgBean = getOssInfo(ossId, false);
			
			if(_orgBean != null && !isEmpty(_orgBean.getOssName())) {
				OssMaster param = new OssMaster();
				List<String> ossNameList = new ArrayList<>();
				ossNameList.add(_orgBean.getOssName());
				String[] ossNames = new String[ossNameList.size()];
				param.setOssNames(ossNameList.toArray(ossNames));
				
				// oss name 또는 nick name으로 참조 가능한 oss 이름만으로 검색한 db 정보
				Map<String, OssMaster> ossMap = getBasicOssInfoList(param);
				
				if(ossMap != null) {
					for(OssMaster _tempBean : ossMap.values()) {
						if(!updateOSSLicenseMap.containsKey(_tempBean.getOssId())) {
							updateOSSLicenseMap.put(_tempBean.getOssId(), _tempBean);
						}
					}
				}
				
				if(!avoidNull(ossMaster.getOssName()).trim().equalsIgnoreCase(_orgBean.getOssName()) 
						|| !avoidNull(ossMaster.getOssVersion()).trim().equalsIgnoreCase(avoidNull(_orgBean.getOssVersion()).trim())) {
					vulnRecheck = true;
				}
			}

		} else { // 신규 등록이지만 version 등록인 경우 v-diff 정보가 업데이트 안되는 현상 대응
			OssMaster param = new OssMaster();
			List<String> ossNameList = new ArrayList<>();
			ossNameList.add(ossMaster.getOssName());
			String[] ossNames = new String[ossNameList.size()];
			param.setOssNames(ossNameList.toArray(ossNames));
			// oss name 또는 nick name으로 참조 가능한 oss 이름만으로 검색한 db 정보
			Map<String, OssMaster> ossMap = getBasicOssInfoList(param);
			
			if(ossMap != null) {
				for(OssMaster _tempBean : ossMap.values()) {
					if(!updateOSSLicenseMap.containsKey(_tempBean.getOssId())) {
						updateOSSLicenseMap.put(_tempBean.getOssId(), _tempBean);
					}
				}
			}
		}
		
		if(vulnRecheck) {
			ossMaster.setVulnRecheck(CoConstDef.FLAG_YES);
		}
		
		ossMaster.setModifier(ossMaster.getLoginUserName());
		
		// trim처리
		ossMaster.setOssName(ossMaster.getOssName().trim());
		
		checkOssLicenseAndObligation(ossMaster);
		
		ossMapper.insertOssMaster(ossMaster);
		ossMapper.deleteOssLicense(ossMaster);
		
		// v-Diff 체크를 위해 license list를 생성
		if("M".equals(ossMaster.getLicenseDiv())) {
			List<OssLicense> list = ossMaster.getOssLicenses();
			int ossLicenseIdx = 0;
			
			for(OssLicense license : list) {
				ossLicenseIdx++;
				
				OssMaster om = new OssMaster(
					  Integer.toString(ossLicenseIdx)
					, ossMaster.getOssId()
					, license.getLicenseName()
					, ossLicenseIdx == 1 ? "" : license.getOssLicenseComb()//ossLicenseIdx가 1일때 Comb 입력안함
					, license.getOssLicenseText()
					, license.getOssCopyright()
					, ossMaster.getLicenseDiv()
				);
				
				ossMapper.insertOssLicense(om);
			}
		} else {
			ossMapper.insertOssLicense(ossMaster);
			// single license의 경우 oss list에서 new 한 경우 osslicense list가 null 이거나, license id가 없는 경우 체워준다.
			if(ossMaster.getOssLicenses() == null || ossMaster.getOssLicenses().isEmpty()) {
				List<OssLicense> _ossLicenseList = new ArrayList<>();
				
				OssLicense _license = new OssLicense();
				_license.setLicenseName(ossMaster.getLicenseName());
				_ossLicenseList.add(_license);
				
				ossMaster.setOssLicenses(_ossLicenseList);
			}
		}		
		
		if(ossMaster.getOssLicenses() != null) {
			for(OssLicense license : ossMaster.getOssLicenses()) {
				// v-Diff check를 위해 만약 license id가 param Bean에 없는 경우, license id를 등록한다.
				if(isEmpty(license.getLicenseId())) {
					if(CoCodeManager.LICENSE_INFO_UPPER.containsKey(license.getLicenseName().toUpperCase())) {
						license.setLicenseId(CoCodeManager.LICENSE_INFO_UPPER.get(license.getLicenseName().toUpperCase()).getLicenseId());
					}
				}
			}
		}
		
		/*
		 * 1. 라이센스 닉네임 삭제 
		 * 2. 라이센스 닉네임 재등록
		 */
		if(CoConstDef.FLAG_YES.equals(ossMaster.getAddNicknameYn())) { //nickname을 clear&insert 하지 않고, 중복제거를 한 나머지 nickname에 대해서는 add함.
			if(ossNicknames != null){
				List<OssMaster> ossNicknameList = ossMapper.selectOssNicknameList(ossMaster);
				
				for(String nickName : ossNicknames){
					if(!isEmpty(nickName)) {
						int duplicateCnt = ossNicknameList.stream().filter(o -> nickName.toUpperCase().equals(o.getOssNickname().toUpperCase())).collect(Collectors.toList()).size();
						
						if(duplicateCnt == 0) {
							OssMaster ossBean = new OssMaster();
							ossBean.setOssName(ossMaster.getOssName());
							ossBean.setOssNickname(nickName.trim());
							
							ossMapper.insertOssNickname(ossBean);
						}
					}
				}
			}
		} else { // nickname => clear&insert
			if(ossNicknames != null) {
				ossMapper.deleteOssNickname(ossMaster);
				
				for(String nickName : ossNicknames){
					if(!isEmpty(nickName)) {
						ossMaster.setOssNickname(nickName.trim());
						ossMapper.insertOssNickname(ossMaster);
					}
				}
			} else {
				ossMapper.deleteOssNickname(ossMaster);
			}
		}
		
		//코멘트 등록
		if(!isEmpty(avoidNull(ossMaster.getComment()).trim())) {
			CommentsHistory param = new CommentsHistory();
			param.setReferenceId(ossMaster.getOssId());
			param.setReferenceDiv(CoConstDef.CD_DTL_COMMENT_OSS);
			param.setContents(ossMaster.getComment());
			
			commentService.registComment(param);
		}
		
		if(isNew || vulnRecheck) {
			List<String> ossNameArr = new ArrayList<>();
			ossNameArr.add(ossMaster.getOssName().trim());
			
			if(ossNicknames != null) {
				for(String s : ossNicknames) {
					if(!isEmpty(s)) {
						ossNameArr.add(s.trim());
					}
				}
			}
			
			OssMaster nvdParam = new OssMaster();
			nvdParam.setOssNames(ossNameArr.toArray(new String[ossNameArr.size()]));
			OssMaster nvdData = null;
			
			if(!isEmpty(ossMaster.getOssVersion())) {
				nvdParam.setOssVersion(ossMaster.getOssVersion());
				nvdData = ossMapper.getNvdDataByOssName(nvdParam);
			} else {
				nvdData = ossMapper.getNvdDataByOssNameWithoutVer(nvdParam);
			}
			
			if(nvdData != null) {
				ossMaster.setCvssScore(nvdData.getCvssScore());
				ossMaster.setCveId(nvdData.getCveId());
				ossMaster.setVulnDate(nvdData.getVulnDate());
				ossMaster.setVulnYn(CoConstDef.FLAG_YES);
				
				ossMapper.updateNvdData(ossMaster);
			} else if(!isNew) {
				OssMaster _orgBean = getOssInfo(ossId, false);
				if(_orgBean != null && (CoConstDef.FLAG_YES.equals(_orgBean.getVulnYn()) || !isEmpty(_orgBean.getCvssScore()))) {
					ossMaster.setVulnYn(CoConstDef.FLAG_NO);
					
					ossMapper.updateNvdData(ossMaster);
				}
			}
		}
		
		// multi / dual /v-diff license 여부 체크
		updateOSSLicenseMap.put(ossMaster.getOssId(), ossMaster);

		for(OssMaster bean : updateOSSLicenseMap.values()) {
			updateLicenseDivDetail(bean);
		}
		
		// download location이 여러건일 경우를 대비해 table을 별도로 관리함.
		registOssDownloadLocation(ossMaster);
		
		return ossMaster.getOssId();
	}

	@Override
	public void registOssDownloadLocation(OssMaster ossMaster) {
		if(ossMapper.existsOssDownloadLocation(ossMaster) > 0){
			ossMapper.deleteOssDownloadLocation(ossMaster);
		}
		
		int idx = 0;
		
		String[] downloadLocations = ossMaster.getDownloadLocations();
		
		if(downloadLocations != null){
			for(String url : downloadLocations){
				if(!isEmpty(url)){ // 공백의 downloadLocation은 save하지 않음.
					OssMaster master = new OssMaster();
					master.setOssId(ossMaster.getOssId());
					master.setDownloadLocation(url);
					master.setSortOrder(Integer.toString(++idx));
					
					ossMapper.insertOssDownloadLocation(master);
				}
			}
		}
	}
	
	@Override
	public Map<String, Object> ossMergeCheckList(OssMaster ossMaster) {
		Map<String, Object> map = new HashMap<String, Object>();
		List<OssMaster> list1 = ossMapper.getBasicOssListByName(CoCodeManager.OSS_INFO_BY_ID.get(ossMaster.getOssId()).getOssName());
		List<OssMaster> list2 = ossMapper.getBasicOssListByName(CoCodeManager.OSS_INFO_BY_ID.get(ossMaster.getNewOssId()).getOssName());
		
		Map<String, OssMaster> mergeMap = new HashMap<>();
		
		// 이관 대상 OSS 정보를 먼저 격납한다.
		for(OssMaster bean : list2) {
			bean.setLicenseName(CommonFunction.makeLicenseExpression(CoCodeManager.OSS_INFO_BY_ID.get(bean.getOssId()).getOssLicenses()));
			bean.setLicenseType(CoCodeManager.getCodeString(CoConstDef.CD_LICENSE_TYPE, bean.getLicenseType()));
			bean.setObligation(CoCodeManager.getCodeString(CoConstDef.CD_OBLIGATION_TYPE, bean.getObligationType()));
			mergeMap.put(avoidNull(bean.getOssVersion(), "N/A").toUpperCase(), bean);
		}
		
		// 삭제 대상 OSS Version이 이관 대상 OSS 에 존재하는지 확인 
		for(OssMaster bean : list1) {
			bean.setLicenseName(CommonFunction.makeLicenseExpression(CoCodeManager.OSS_INFO_BY_ID.get(bean.getOssId()).getOssLicenses()));
			bean.setLicenseType(CoCodeManager.getCodeString(CoConstDef.CD_LICENSE_TYPE, bean.getLicenseType()));
			bean.setObligation(CoCodeManager.getCodeString(CoConstDef.CD_OBLIGATION_TYPE, bean.getObligationType()));
			
			String verKey = avoidNull(bean.getOssVersion(), "N/A").toUpperCase();
			
			// 이미 존재한다면
			if(mergeMap.containsKey(verKey)) {
				mergeMap.get(verKey).setMergeStr("Duplicated");
				mergeMap.get(verKey).setDelOssId(bean.getOssId());
			} else {
				bean.setMergeStr("Added");
				mergeMap.put(verKey, bean);
			}
		}
		
		Map<String, OssMaster> treeMap = new TreeMap<>(mergeMap);
		
		map.put("rows", new ArrayList<>(treeMap.values()));
		
		return map;
	}
	
	@SuppressWarnings("unchecked")
	@Transactional
	@Override
	@CacheEvict(value="autocompleteCache", allEntries=true)
	public int deleteOssMaster(OssMaster ossMaster) {
		int result = 1;
		log.debug("DELETE OSS");

		try {
			OssMaster beforeBean = getOssInfo(ossMaster.getOssId(), false);
			
			if(isEmpty(ossMaster.getOssName())) {
				ossMaster.setOssName(beforeBean.getOssName());
			}
			
			// 바로 삭제 일 경우( identification 상태가 conf인 프로젝트가 없을시 )
			if(CoConstDef.FLAG_NO.equals(avoidNull(ossMaster.getNewOssId(), CoConstDef.FLAG_NO))) {
				//3. 기존의 Oss 삭제		
				//ossMapper.deleteOssNickname(ossMaster);
				
				// 닉네임은 이름으로 매핑되기 때문에, 삭제후에 신규 추가시 자동으로 설정되는 문제가 있음
				// 삭제하는 oss 이름으로 공유하는 닉네임이 더이상 없을 경우, 닉네임도 삭제하도록 추가
				if(ossMapper.checkHasAnotherVersion(ossMaster) == 0) {
					ossMapper.deleteOssNickname(ossMaster);
				}
				
				ossMapper.deleteOssLicense(ossMaster);
				ossMapper.deleteOssDownloadLocation(ossMaster);
				ossMapper.deleteOssMaster(ossMaster);
			
			} else {
				// 동일한 oss에서 이동하는 경우, nick name을 별도로 등록하지 않음
				OssMaster afterBean = getOssInfo(ossMaster.getNewOssId(), false);
				
				if(!beforeBean.getOssName().toUpperCase().equals(afterBean.getOssName().toUpperCase())) {
					//2. 기존 Oss 의 Name 과 Nickname을 현재 선택한 Oss의 Nickname 에 병합
					ArrayList<String> nickNamesArray = new ArrayList<>();
					Map<String, Object> map = ossMapper.selectOssNameMap(ossMaster);
					String ossName = (String)map.get("ossName");
					List<Map<String, String>> list = (List<Map<String, String>>) map.get("nicknameList");
					
					nickNamesArray.add(ossName);
					
					for(Map<String, String> nickMap : list){
						nickNamesArray.addAll(new ArrayList<String>(nickMap.values()));
					}
					
					for(String nickname : nickNamesArray){
						ossMaster.setOssNickname(nickname);
						ossMapper.mergeOssNickname(ossMaster);
					}
				}
				
				if(ossMapper.checkHasAnotherVersion(ossMaster) == 0) {
					ossMapper.deleteOssNickname(ossMaster);
				}
				
				ossMapper.deleteOssLicense(ossMaster);
				ossMapper.deleteOssDownloadLocation(ossMaster);
				ossMapper.deleteOssMaster(ossMaster);
			}

			// v-diff 체크
			{
				// version 에 따라 라이선스가 달라지는지 체크 (v-diff)
				boolean vDiffFlag = false;
				OssMaster param = new OssMaster();
				List<String> ossNameList = new ArrayList<>();
				ossNameList.add(beforeBean.getOssName());
				String[] ossNames = new String[ossNameList.size()];
				param.setOssNames(ossNameList.toArray(ossNames));
				// oss name 또는 nick name으로 참조 가능한 oss 이름만으로 검색한 db 정보
				Map<String, OssMaster> ossMap = getBasicOssInfoList(param);
				// size가 1개인 경우는 처리할 필요 없음
				List<String> ossIdListByName = new ArrayList<>();
				
				if(ossMap != null && ossMap.size() > 1) {
					String _key = "";
					
					for(OssMaster _bean : ossMap.values()) {
						if(!isEmpty(_bean.getOssId())) {
							ossIdListByName.add(_bean.getOssId());
							
							if(!vDiffFlag && _bean.getOssLicenses() != null) {
								if(isEmpty(_key)) {
									// 비교대상 설정 처음 한번만
									_key = makeLicenseIdKeyStr(_bean.getOssLicenses());
								} else if(!beforeBean.getOssId().equals(_bean.getOssId()) &&  !_key.equals(makeLicenseIdKeyStr(_bean.getOssLicenses()))) { // 삭제대상은 제외하고 license 정보가 다른 oss가 존재하는지 체크
									vDiffFlag = true;
								}
							}
						}
					}
				}
				
				if(ossIdListByName != null && !ossIdListByName.isEmpty()) {
					// v-diff flag 업데이트
					OssMaster updateParam = new OssMaster();
					updateParam.setOssIdList(ossIdListByName);
					updateParam.setVersionDiffFlag(vDiffFlag ? CoConstDef.FLAG_YES : CoConstDef.FLAG_NO);
					
					ossMapper.updateOssLicenseVDiffFlag(updateParam);
				}
			}
		} catch(Exception e) {
			result = 0;
			log.error("FAILED TO REMOVE OSS DATA.", e);
		}
		
		return result;
	}

	@Override
	public OssMaster checkExistsOss(OssMaster param) {
		OssMaster bean = ossMapper.checkExistsOss(param);
		
		if(bean != null && !isEmpty(bean.getOssId())) {
			return bean;
		}
		
		return null;
	}

	@Override
	public OssMaster checkExistsOssNickname(OssMaster param) {
		OssMaster bean1 = ossMapper.checkExistsOssname(param);
		
		if(bean1 != null && !isEmpty(bean1.getOssId())) {
			return bean1;
		}
		
		OssMaster bean2 = ossMapper.checkExistsOssNickname(param);
		
		if(bean2 != null && !isEmpty(bean2.getOssId())) {
			return bean2;
		}
		
		if(!isEmpty(param.getOssId())) {
			OssMaster bean3 = ossMapper.checkExistsOssNickname2(param);
			
			if(bean3 != null) {
				return bean3;
			}
			
		}
		
		return null;
	}

	@Override
	public OssMaster checkExistsOssNickname2(OssMaster param) {
		OssMaster bean2 = ossMapper.checkExistsOssNickname(param);
		
		if(bean2 != null && !isEmpty(bean2.getOssId())) {
			return bean2;
		}
		
		return null;
	}

	@Override
	public Map<String, OssMaster> getBasicOssInfoListById(OssMaster ossMaster) {
		Map<String, OssMaster> resultMap = new HashMap<>();
		
		if(CoCodeManager.OSS_INFO_BY_ID != null && ossMaster.getOssIdList() != null && !ossMaster.getOssIdList().isEmpty()) {
			for(String ossId : ossMaster.getOssIdList()) {
				if(CoCodeManager.OSS_INFO_BY_ID.containsKey(ossId)) {
					resultMap.put(ossId, CoCodeManager.OSS_INFO_BY_ID.get(ossId));
				}
			}
		}
		
		return resultMap;
	}

	@Override
	public List<OssMaster> getOssListByName(OssMaster bean) {
		List<OssMaster> list = ossMapper.getOssListByName(bean);
		
		if(list == null) {
			list = new ArrayList<>();
		}
		
		// oss id로 취합(라이선스 정보)
		List<OssMaster> newList = new ArrayList<>();
		Map<String, OssMaster> remakeMap = new HashMap<>();
		OssMaster currentBean = null;
		for(OssMaster ossBean : list) {
			// name + version
			if(!isEmpty(ossBean.getOssVersion())) {
				ossBean.setOssNameVerStr(ossBean.getOssName() + " (" + ossBean.getOssVersion() + ")");
			} else {
				ossBean.setOssNameVerStr(ossBean.getOssName());
			}
			
			if(remakeMap.containsKey(ossBean.getOssId())) {
				currentBean = remakeMap.get(ossBean.getOssId());
			} else {
				currentBean = ossBean;
				
				if(!isEmpty(currentBean.getOssNickname())) {
					currentBean.setOssNickname(currentBean.getOssNickname().replaceAll("\\|", "<br>"));
				}
				
				currentBean.setCopyright(CommonFunction.lineReplaceToBR(ossBean.getCopyright()));
				currentBean.setSummaryDescription(CommonFunction.lineReplaceToBR(ossBean.getSummaryDescription()));
			}
			
			OssLicense licenseBean = new OssLicense();
			licenseBean.setLicenseId(ossBean.getLicenseId());
			licenseBean.setOssLicenseIdx(ossBean.getOssLicenseIdx());
			licenseBean.setLicenseName(ossBean.getLicenseName());
			licenseBean.setOssLicenseComb(ossBean.getOssLicenseComb());
			licenseBean.setOssLicenseText(CommonFunction.lineReplaceToBR(ossBean.getOssLicenseText()));
			licenseBean.setOssCopyright(CommonFunction.lineReplaceToBR(ossBean.getOssCopyright()));
			
			currentBean.addOssLicense(licenseBean);

			if(remakeMap.containsKey(ossBean.getOssId())) {
				remakeMap.replace(ossBean.getOssId(), currentBean);
			} else {
				remakeMap.put(ossBean.getOssId(), currentBean);
			}
		}
		
		for(OssMaster ossBean : remakeMap.values()) {
			ossBean.setLicenseName(CommonFunction.makeLicenseExpression(ossBean.getOssLicenses(), !isEmpty(bean.getOssId())));
			newList.add(ossBean);
		}
		
		return newList;
	}
	
	@Override
	public void updateLicenseDivDetail(OssMaster master) {
		if(master != null && !isEmpty(master.getOssId())) {
			boolean multiLicenseFlag = false;
			boolean dualLicenseFlag = false;
			boolean vDiffFlag = false;
			
			if(CoConstDef.LICENSE_DIV_MULTI.equals(master.getLicenseDiv())) {
				for(OssLicense license : master.getOssLicenses()) {
					// AND 조건이 존재할 경우 MULTI
					// OR 조건이 존재ㅏㄹ 경우 DUAL
					if("AND".equalsIgnoreCase(license.getOssLicenseComb())) {
						multiLicenseFlag = true;
					}
					
					if("OR".equalsIgnoreCase(license.getOssLicenseComb())) {
						dualLicenseFlag = true;
					}
				}
			}

			// version 에 따라 라이선스가 달라지는지 체크 (v-diff)
			OssMaster param = new OssMaster();
			List<String> ossNameList = new ArrayList<>();
			ossNameList.add(master.getOssName());
			String[] ossNames = new String[ossNameList.size()];
			param.setOssNames(ossNameList.toArray(ossNames));
			// oss name 또는 nick name으로 참조 가능한 oss 이름만으로 검색한 db 정보
			Map<String, OssMaster> ossMap = getBasicOssInfoList(param);
			// size가 1개인 경우는 처리할 필요 없음
			List<String> ossIdListByName = new ArrayList<>();
			ossIdListByName.add(master.getOssId());
			
			if(ossMap != null && ossMap.size() > 1) {
				// 비교대상 key룰 먼저 설정
				String _key = makeLicenseIdKeyStr(master.getOssLicenses());
				
				for(OssMaster _bean : ossMap.values()) {
					if(!isEmpty(_bean.getOssId())) {
						ossIdListByName.add(_bean.getOssId());
						
						if(!vDiffFlag && _bean.getOssLicenses() != null) {
							if(!_key.equals(makeLicenseIdKeyStr(_bean.getOssLicenses()))) {
								vDiffFlag = true;
							}
						}
					}
				}
			}
			
			//multi / dual 여부 flag 업데이트
			OssMaster updateParam = new OssMaster();
			updateParam.setOssId(master.getOssId());
			updateParam.setMultiLicenseFlag(multiLicenseFlag ? CoConstDef.FLAG_YES : CoConstDef.FLAG_NO);
			updateParam.setDualLicenseFlag(dualLicenseFlag ? CoConstDef.FLAG_YES : CoConstDef.FLAG_NO);
			
			ossMapper.updateOssLicenseFlag(updateParam);
			
			updateParam.setOssIdList(ossIdListByName);
			updateParam.setVersionDiffFlag(vDiffFlag ? CoConstDef.FLAG_YES : CoConstDef.FLAG_NO);
			
			ossMapper.updateOssLicenseVDiffFlag(updateParam);		
		}
	}

	@Override
	public OssMaster getLastModifiedOssInfoByName(OssMaster bean) {
		return ossMapper.getLastModifiedOssInfoByName(bean);
	}

	@SuppressWarnings("unchecked")
	@Override
	public String checkVdiff(Map<String, Object> reqMap) {
		boolean vDiffFlag = false;
		// version 에 따라 라이선스가 달라지는지 체크 (v-diff)
		OssMaster param = new OssMaster();
		String ossId = avoidNull((String) reqMap.get("ossId"));
		String ossName = (String) reqMap.get("ossName");
		List<OssLicense> license = (List<OssLicense>) reqMap.get("license");
		String[] ossNames = new String[1];
		ossNames[0] = ossName;
		param.setOssNames(ossNames);

		// oss name 또는 nick name으로 참조 가능한 oss 이름만으로 검색한 db 정보
		Map<String, OssMaster> ossMap = getBasicOssInfoList(param);

		// size가 1개인 경우는 처리할 필요 없음
		List<String> ossIdListByName = new ArrayList<>();
		ossIdListByName.add(ossId);
		
		if(ossMap != null && !ossMap.isEmpty()) {
			// 비교대상 key룰 먼저 설정
			String _key = makeLicenseIdKeyStr(license);
			
			for(OssMaster _bean : ossMap.values()) {
				if(!isEmpty(_bean.getOssId())) {
					ossIdListByName.add(_bean.getOssId());
					if(_bean.getOssLicenses() != null && !ossId.equals(_bean.getOssId())) {
						if(!_key.equals(makeLicenseIdKeyStr(_bean.getOssLicenses()))) {
							vDiffFlag = true;
							break;
						}
					}
				}
			}
		}
		
		return vDiffFlag ? CoConstDef.FLAG_YES : CoConstDef.FLAG_NO;
	}
	
	private String makeLicenseIdKeyStr(List<OssLicense> list) {
		String rtnVal = "";
		List<String> licenseIdList = new ArrayList<>();
		
		if(list != null) {
			for(OssLicense bean : list) {
				licenseIdList.add(bean.getLicenseId());
			}
		}
		
		Collections.sort(licenseIdList);
		
		for(String s : licenseIdList) {
			if(!isEmpty(rtnVal)) {
				rtnVal += "-";
			}
			
			rtnVal += s;
		}

		return rtnVal;
	}
	
	@Override
	public void checkOssLicenseAndObligation(OssMaster ossMaster) {
		if("M".equals(ossMaster.getLicenseDiv())) {
			List<List<OssLicense>> orLicenseList = new ArrayList<>();
			String currentType = null;
			String currentObligation = null;
			boolean isFirst = true;
			List<OssLicense> andLicenseList = new ArrayList<>();
			
			for(OssLicense license : ossMaster.getOssLicenses()) {
				LicenseMaster master = CoCodeManager.LICENSE_INFO_UPPER.get(license.getLicenseName().toUpperCase());
				license.setLicenseType(master.getLicenseType());
				
				// obligation 설정
				if(CoConstDef.FLAG_YES.equals(master.getObligationNeedsCheckYn())) {
					license.setObligation(CoConstDef.CD_DTL_OBLIGATION_NEEDSCHECK);
				} else if(CoConstDef.FLAG_YES.equals(master.getObligationDisclosingSrcYn())) {
					license.setObligation(CoConstDef.CD_DTL_OBLIGATION_DISCLOSURE);
				} else if(CoConstDef.FLAG_YES.equals(master.getObligationNotificationYn())) {
					license.setObligation(CoConstDef.CD_DTL_OBLIGATION_NOTICE);
				}
				
				if(!isFirst && "OR".equalsIgnoreCase(license.getOssLicenseComb()) ) {
					if(!andLicenseList.isEmpty()) {
						orLicenseList.add(andLicenseList);
						andLicenseList = new ArrayList<>();
						andLicenseList.add(license);
					}
					
					andLicenseList = new ArrayList<>();
					andLicenseList.add(license);
				} else {
					andLicenseList.add(license);
				}
				
				isFirst = false;
			}

			if(!andLicenseList.isEmpty()) {
				orLicenseList.add(andLicenseList);
			}
			
			//  or인 경우는 Permissive한 걸로, and인 경우는 Copyleft 가 강한 것으로 표시
			for(List<OssLicense> andlicenseGroup : orLicenseList) {
				OssLicense permissiveLicense = CommonFunction.getLicensePermissiveTypeLicense(andlicenseGroup);
				
				if(permissiveLicense != null) {
					switch (permissiveLicense.getLicenseType()) {
						case CoConstDef.CD_LICENSE_TYPE_PMS:
							currentType = CoConstDef.CD_LICENSE_TYPE_PMS;
							
							currentObligation = CommonFunction.getObligationTypeWithAndLicense(andlicenseGroup);
							
							break;
						case CoConstDef.CD_LICENSE_TYPE_WCP:
							if(!CoConstDef.CD_LICENSE_TYPE_PMS.equals(currentType)) {
								currentType = CoConstDef.CD_LICENSE_TYPE_WCP;
								
								currentObligation = CommonFunction.getObligationTypeWithAndLicense(andlicenseGroup);
							}
							
							break;
						case CoConstDef.CD_LICENSE_TYPE_CP:
							if(isEmpty(currentType)) {
								currentType = CoConstDef.CD_LICENSE_TYPE_CP;
								
								currentObligation = CommonFunction.getObligationTypeWithAndLicense(andlicenseGroup);
							}
							
							break;
						default:
							break;
					}
				}
			}
			
			ossMaster.setLicenseType(currentType);
			ossMaster.setObligationType(currentObligation);
		} else {
			LicenseMaster master = CoCodeManager.LICENSE_INFO_UPPER.get(ossMaster.getLicenseName().toUpperCase());
			
			if(master == null) {
				log.error("############# Can not found license info : " + ossMaster.getLicenseName());
			}
			
			ossMaster.setLicenseType(master.getLicenseType());
			String obligationType = "";
			
			if(CoConstDef.FLAG_YES.equals(master.getObligationNeedsCheckYn())) {
				obligationType = CoConstDef.CD_DTL_OBLIGATION_NEEDSCHECK;
			} else if(CoConstDef.FLAG_YES.equals(master.getObligationDisclosingSrcYn())) {
				obligationType = CoConstDef.CD_DTL_OBLIGATION_DISCLOSURE;
			} else if(CoConstDef.FLAG_YES.equals(master.getObligationNotificationYn())) {
				obligationType = CoConstDef.CD_DTL_OBLIGATION_NOTICE;
			}
			
			ossMaster.setObligationType(obligationType);
		}
	}

	@Override
	public void updateLicenseTypeAndObligation(OssMaster ossBean) {
		ossMapper.updateLicenseTypeAndObligation(ossBean);
	}

	@Override
	public Map<String, Object> checkExistsOssDownloadLocation(OssMaster ossMaster) {
		Map<String, Object> returnMap = new HashMap<String, Object>();
		returnMap.put("downloadLocation", ossMapper.checkExistsOssDownloadLocation(ossMaster));
		
		return returnMap;
	}

	@Override
	public Map<String, Object> checkExistsOssHomepage(OssMaster ossMaster) {
		Map<String, Object> returnMap = new HashMap<String, Object>();
		returnMap.put("homepage", ossMapper.checkExistsOssHomepage(ossMaster));
		
		return returnMap;
	}

	@Override
	public Map<String, Object> checkExistsOssDownloadLocationWithOssName(OssMaster param) {
		Map<String, Object> returnMap = new HashMap<String, Object>();
		returnMap.put("downloadLocation", ossMapper.checkExistsOssDownloadLocationWithOssName(param));
		
		return returnMap;
	}

	@Override
	public Map<String, Object> checkExistsOssHomepageWithOssName(OssMaster param) {
		Map<String, Object> returnMap = new HashMap<String, Object>();
		returnMap.put("homepage", ossMapper.checkExistsOssHomepageWithOssName(param));
		
		return returnMap;
	}

	@Override
	public Map<String, OssMaster> getBasicOssInfoList(OssMaster ossMaster) {
		return getBasicOssInfoList(ossMaster, false);
	}

	@Override
	public Map<String, OssMaster> getBasicOssInfoList(OssMaster ossMaster, boolean useUpperKey) {
		List<OssMaster> list = ossMapper.getBasicOssInfoList(ossMaster);
		
		return makeBasicOssInfoMap(list, false, useUpperKey);
	}	
	
	@Override
	public int checkExistsOssByname(OssMaster bean) {
		return ossMapper.checkExistsOssByname(bean);
	}
	
	@Override
	public List<ProjectIdentification> checkOssName(List<ProjectIdentification> list){
		List<ProjectIdentification> result = new ArrayList<ProjectIdentification>();
		List<String> checkOssNameUrl = CoCodeManager.getCodeNames(CoConstDef.CD_CHECK_OSS_NAME_URL);
		int urlSearchSeq = -1;
		
		list = list.stream().filter(CommonFunction.distinctByKey(p -> p.getOssName()+"-"+p.getDownloadLocation())).collect(Collectors.toList());
		
		for(ProjectIdentification bean : list) {
			int seq = 0;
			
			if(isEmpty(bean.getDownloadLocation())) {
				continue;
			}
			
			try {
				boolean semicolonFlag = bean.getDownloadLocation().contains(";");
				
				for(String url : checkOssNameUrl) {
					if(urlSearchSeq == -1 && bean.getDownloadLocation().contains(url)) {
						urlSearchSeq = seq;
						break;
					}
					seq++;
				}
				
				if( urlSearchSeq > -1 ) {
					String downloadlocationUrl = bean.getDownloadLocation();
					Pattern p = null;
					
					switch(urlSearchSeq) {
						case 0: // github
							p = Pattern.compile("((http|https)://github.com/([^/]+)/([^/]+))");
							
							break;
						case 1: // npm
							p = Pattern.compile("((http|https)://www.npmjs.com/package/([^/]+))");
							
							break;
						case 2: // pypi
							p = Pattern.compile("((http|https)://pypi.org/project/([^/]+))");
							
							break;
						case 3: // maven
							p = Pattern.compile("((http|https)://mvnrepository.com/artifact/([^/]+)/([^/]+))");
							
							break;
						default:
							break;
					}
					
					Matcher m = p.matcher(downloadlocationUrl);
					
					while(m.find()) {
						bean.setDownloadLocation(m.group(0));
					}
					
					int cnt = ossMapper.checkOssNameUrlCnt(bean);
					
					if(cnt == 0) {
						List<OssMaster> ossNameList = ossMapper.checkOssNameUrl(bean);
						String checkName = "";
						
						for(OssMaster ossBean : ossNameList) {
							if(!isEmpty(checkName)) {
								checkName += "|";
							}
							
							checkName += ossBean.getOssName();
						}
						
						if(!isEmpty(checkName)) {
							bean.setDownloadLocation(downloadlocationUrl);
							bean.setCheckName(checkName);
							result.add(bean);
						}
					}
				} else if(semicolonFlag) {
					String downloadlocationUrl = bean.getDownloadLocation().split(";")[0];
					
					if(downloadlocationUrl.startsWith("http://") 
							|| downloadlocationUrl.startsWith("https://")
							|| downloadlocationUrl.startsWith("git://")
							|| downloadlocationUrl.startsWith("ftp://")
							|| downloadlocationUrl.startsWith("svn://")) {
						downloadlocationUrl = downloadlocationUrl.split("//")[1];
					}
					
					if(downloadlocationUrl.startsWith("www")) {
						downloadlocationUrl = downloadlocationUrl.substring(4, downloadlocationUrl.length());
					}
					
					if(downloadlocationUrl.endsWith("git")) {
						downloadlocationUrl = downloadlocationUrl.substring(0, downloadlocationUrl.length()-4);
					}
					
					bean.setDownloadLocation(downloadlocationUrl);
					
					int cnt = ossMapper.checkOssNameUrl2Cnt(bean);
					
					if(cnt == 0) {
						List<OssMaster> ossNameList = ossMapper.checkOssNameUrl2(bean);
						String checkName = "";
						
						for(OssMaster ossBean : ossNameList) {
							if(!isEmpty(checkName)) {
								checkName += "|";
							}
							
							checkName += ossBean.getOssName();
						}
						
						if(!isEmpty(checkName)) {
							bean.setDownloadLocation(downloadlocationUrl);
							bean.setCheckName(checkName);
							result.add(bean);
						}
					}
				} else {
					int cnt = ossMapper.checkOssNameCnt(bean);
					
					if(cnt == 0) {
						String checkName = ossMapper.checkOssName(bean);
						
						if(!isEmpty(checkName)) {
							bean.setCheckName(checkName);
							result.add(bean);
						}
					}
				}
				
				urlSearchSeq = -1;
			} catch (Exception e) {
				log.error(e.getMessage());
			}
		}
		
		final Comparator<ProjectIdentification> comp = (p1, p2) -> Integer.compare(StringUtil.countMatches(p1.getCheckName(), ","), StringUtil.countMatches(p2.getCheckName(), ","));
		
		// oss name과 registered oss name은 unique하게 출력
		List<ProjectIdentification> sortedData = result.stream().filter(CommonFunction.distinctByKey(p -> p.getOssName()+p.getCheckName())).sorted(comp).collect(Collectors.toList());
		
		// oss name과 registered oss name이 unique하지 않다면 중복된 data의 downloadlocation을 전부 합쳐서 출력함. 
		for(ProjectIdentification p : sortedData) {
			String downloadLocation = result.stream()
											.filter(e -> (e.getOssName()+e.getCheckName()).equals(p.getOssName()+p.getCheckName()))
											.map(e -> e.getDownloadLocation())
											.collect(Collectors.joining(","));
			
			p.setDownloadLocation(downloadLocation);
		}
		
		return sortedData;
	}
	
	@Transactional
	@Override
	public Map<String, Object> saveOssCheckName(ProjectIdentification paramBean, String targetName) {
		Map<String, Object> map = new HashMap<String, Object>();
		try {
			int updateCnt = 0;
			
			List<String> checkOssNameUrl = CoCodeManager.getCodeNames(CoConstDef.CD_CHECK_OSS_NAME_URL);
			int urlSearchSeq = -1;
			int seq = 0;
			
			for(String url : checkOssNameUrl) {
				if(urlSearchSeq == -1 && paramBean.getDownloadLocation().contains(url)) {
					urlSearchSeq = seq;
					
					break;
				}
				
				seq++;
			}
			
			if( urlSearchSeq > -1 ) {
				String downloadlocationUrl = paramBean.getDownloadLocation().split("<br>")[0];
				Pattern p = null;
				
				switch(urlSearchSeq) {
					case 0: // github
						p = Pattern.compile("((http|https)://github.com/([^/]+)/([^/]+))");
						
						break;
					case 1: // npm
						p = Pattern.compile("((http|https)://www.npmjs.com/package/([^/]+))");
						
						break;
					case 2: // pypi
						p = Pattern.compile("((http|https)://pypi.org/project/([^/]+))");
						
						break;
					case 3: // maven
						p = Pattern.compile("((http|https)://mvnrepository.com/artifact/([^/]+)/([^/]+))");
						
						break;
					default:
						break;
				}
				
				Matcher m = p.matcher(downloadlocationUrl);
				
				while(m.find()) {
					paramBean.setDownloadLocation(m.group(0));
				}
			}
			
			switch(targetName.toUpperCase()) {
				case CoConstDef.CD_CHECK_OSS_NAME_SELF:
					String[] gridId = paramBean.getGridId().split("-");
					paramBean.setGridId(gridId[0]+"-"+gridId[1]);
					
					updateCnt = ossMapper.updateOssCheckNameBySelfCheck(paramBean);
					
					break;
				case CoConstDef.CD_CHECK_OSS_NAME_IDENTIFICATION:
					updateCnt = ossMapper.updateOssCheckName(paramBean);
					
					String commentId = paramBean.getReferenceId();
					String checkOssNameComment = "";
					String changeOssNameInfo = "<p>" + paramBean.getOssName() + " => " + paramBean.getCheckName() + "</p>";
					CommentsHistory commentInfo = null;
					
					if(isEmpty(commentId)) {
						checkOssNameComment  = "<p><b>The following open source and license names will be changed to names registered on the system for efficient management.</b></p>";
						checkOssNameComment += "<p><b>Opensource Names</b></p>";
						checkOssNameComment += changeOssNameInfo;
						CommentsHistory commHisBean = new CommentsHistory();
						commHisBean.setReferenceDiv(CoConstDef.CD_DTL_COMMENT_IDENTIFICAITON_HIS);
						commHisBean.setReferenceId(paramBean.getRefPrjId());
						commHisBean.setContents(checkOssNameComment);
						commentInfo = commentService.registComment(commHisBean, false);
					} else {
						commentInfo = (CommentsHistory) commentService.getCommnetInfo(commentId).get("info");
						
						if(commentInfo != null) {
							if(!isEmpty(commentInfo.getContents())) {
								checkOssNameComment  = commentInfo.getContents();
								checkOssNameComment += changeOssNameInfo;
								commentInfo.setContents(checkOssNameComment);
								
								commentService.updateComment(commentInfo, false);
							}
						}
					}
					
					if(commentInfo != null) {
						map.put("commentId", commentInfo.getCommId());
					}
					
					break;
			}
			
			if(updateCnt >= 1) {
				map.put("isValid", true);
				map.put("returnType", "Success");
			} else {
				throw new Exception("update Cnt가 비정상적인 값임.");
			}
		} catch (Exception e) {
			log.error(e.getMessage());
			
			map.put("isValid", false);
			map.put("returnType", "");
		}
		
		return map;
	}
	
	@Transactional
	@Override
	public Map<String, Object> saveOssNickname(ProjectIdentification paramBean) {
		Map<String, Object> map = new HashMap<String, Object>();
		OssMaster ossMaster = new OssMaster();
		ossMaster.setOssName(paramBean.getCheckName());
		ossMaster.setOssNickname(paramBean.getOssName());
		
		try {
			if(isEmpty(ossMaster.getOssNickname())) {
				throw new Exception(ossMaster.getOssName() + " -> NickName field is required.");
			}
			
			int insertCnt = ossMapper.insertOssNickname(ossMaster);
			
			if(insertCnt == 1) {
				map.put("isValid", true);
				map.put("returnType", "Success");
			} else {
				throw new Exception("update Cnt가 비정상적인 값임.");
			}
		} catch (Exception e) {
			log.error(e.getMessage());
			
			map.put("isValid", false);
			map.put("returnType", e.getMessage());
		}
		
		return map;
	}
	
	@Transactional
	@Override
	public Map<String, Object> saveOssAnalysisList(OssMaster ossBean, String key) {
		Map<String, Object> result = new HashMap<String, Object>();
		
		try {
			switch(key) {
				case "BOM":
					int analysisListCnt = ossMapper.ossAnalysisListCnt(ossBean);
					
					if(analysisListCnt > 0) {
						ossMapper.deleteOssAnalysisList(ossBean);
					}
					
					if(ossBean.getComponentIdList().size() > 0) {
						int insertCnt = ossMapper.insertOssAnalysisList(ossBean);
						
						if(insertCnt <= 0) {
							result.put("isValid", false);
							result.put("returnType", "Failed");
						}
					}
					
					break;
				case "POPUP":
					int updateCnt = ossMapper.updateOssAnalysisList(ossBean);
					
					if(updateCnt != 1) {
						result.put("isValid", false);
						result.put("returnType", "Failed");
					}
					
					break;
			}
		} catch (Exception e) {
			log.error(e.getMessage());
		}
		
		if(result.keySet().size() == 0) {
			result.put("isValid", true);
			result.put("returnType", "Success");
		}
		
		return result;
	}
	
	@Override
	public Map<String, Object> getOssAnalysisList(OssMaster ossMaster) {
		Map<String, Object> result = new HashMap<String, Object>();
		List<OssAnalysis> list = null;
		
		if(CoConstDef.FLAG_YES.equals(ossMaster.getStartAnalysisFlag())) {
			int records = ossMapper.ossAnalysisListCnt(ossMaster);
			ossMaster.setTotListSize(records);
			
			list = ossMapper.selectOssAnalysisList(ossMaster);
			
			result.put("page", ossMaster.getCurPage());
			result.put("total", ossMaster.getTotBlockSize());
			result.put("records", records);
		}
		
		if(!CoConstDef.FLAG_YES.equals(ossMaster.getStartAnalysisFlag())) {
			list = ossMapper.selectOssAnalysisList(ossMaster);
			CommonFunction.getAnalysisValidation(result, list);
		}
		
		result.put("rows", list);
		
		return result;
	}
	
	@Override
	public int getAnalysisListPage(int rows, String prjId) {
		try {
			return ossMapper.getAnalysisListPage(rows, prjId);
		} catch (Exception e) {
			return 1;
		}
	}
	
	@Override
	public Map<String, Object> startAnalysis(String prjId, String fileSeq){
		Map<String, Object> resultMap = new HashMap<String, Object>();
		T2File fileInfo = new T2File();
		
		InputStream is = null;
		InputStreamReader isr = null;
		BufferedReader br = null;
		BufferedReader error = null;
		Process process = null;
		boolean isProd = "REAL".equals(avoidNull(CommonFunction.getProperty("server.mode")));
		
		try {			
			log.info("ANALYSIS START PRJ ID : "+prjId+" ANALYSIS file ID : " + fileSeq);
			
			fileInfo = fileMapper.selectFileInfo(fileSeq);
			
			String EMAIL_VAL = projectMapper.getReviewerEmail(prjId, loginUserName()); // reviewer와 loginUser의 email
			String analysisCommand = MessageFormat.format(CommonFunction.getProperty("autoanalysis.ssh.command"), (isProd ? "live" : "dev"), prjId, fileInfo.getLogiNm(), EMAIL_VAL, (isProd ? 0 : 1));
			
			ProcessBuilder builder = new ProcessBuilder( "/bin/bash", "-c", analysisCommand );
			
			builder.redirectErrorStream(true);
		
			process = builder.start();
			log.info("ANALYSIS Process PRJ ID : " + prjId + " command : " + analysisCommand);
			
			is = process.getInputStream();
			isr = new InputStreamReader(is);
			br = new BufferedReader(isr);
			
			int count = 0;
			int interval = 1000; // 1 sec
			int idleTime = Integer.parseInt(CoCodeManager.getCodeExpString(CoConstDef.CD_AUTO_ANALYSIS, CoConstDef.CD_IDLE_TIME));
			
			while (!Thread.currentThread().isInterrupted()) {
				if(count > idleTime) {
					log.info("ANALYSIS TIMEOUT PRJ ID : " + prjId);
					resultMap.put("isValid", false);
					resultMap.put("returnMsg", "OSS auto analysis has not been completed yet.");
					
					break;
				}
				
				String result = br.readLine();
				log.info("OSS AUTO ANALYSIS READLINE : " + result);
				
				if(result.toLowerCase().indexOf("start download oss") > -1) {
					log.info("ANALYSIS START SUCCESS PRJ ID : " + prjId);
					Project prjInfo = new Project();
					prjInfo.setPrjId(prjId);
					
					// script가 success일때 status를 progress로 변경함.
					OssMaster ossBean = new OssMaster();
					ossBean.setPrjId(prjId);
					ossBean.setCreator(loginUserName());
					ossMapper.setOssAnalysisStatus(ossBean);
					
					prjInfo = projectMapper.getOssAnalysisData(prjInfo);
					
					resultMap.put("isValid", true);
					resultMap.put("returnMsg", "Success");
					resultMap.put("prjInfo", prjInfo);
					
					break;
				}
				
				count++;
				
				Thread.sleep(interval);
			}
			// 스크립트 종료
		} catch(NullPointerException npe) {
			log.error("ANALYSIS ERR PRJ ID : " + prjId);
			log.error(npe.getMessage(), npe);
			
			resultMap.replace("isValid", false);
			resultMap.replace("returnMsg", "script Error");
		} catch (Exception e) {
			log.error("ANALYSIS ERR PRJ ID : " + prjId);
			log.error(e.getMessage(), e);
			
			resultMap.replace("isValid", false);
			resultMap.replace("returnMsg", "OSS auto analysis has not been completed yet.");
		} finally {
			if(br != null) {
				try {
					br.close();
				} catch (Exception e2) {}
			}
			
			if(error != null) {
				try {
					error.close();
				} catch (Exception e2) {}
			}
			
			if(isr != null) {
				try {
					isr.close();
				} catch (Exception e2) {}
			}
			
			if(is != null) {
				try {
					is.close();
				} catch (Exception e2) {}
			}
			
			if(error != null) {
				try {
					error.close();
				} catch (Exception e2) {}
			}
			
			try {
				if(process != null) {
					log.info("Do OSS ANALYSIS Process Destry");
					process.destroy();
				}
			} catch (Exception e2) {
				log.error(e2.getMessage(), e2);
			}
		}
		
		return resultMap;
	}

	@Override
	public OssAnalysis getNewestOssInfo(OssAnalysis bean) {		
		OssAnalysis ossNewistData = ossMapper.getNewestOssInfo(bean);
		
		if(ossNewistData != null) {
			if(!isEmpty(ossNewistData.getDownloadLocationGroup())) {
				String url = "";
				
				String[] downloadLocationList = (ossNewistData.getDownloadLocation()+","+ossNewistData.getDownloadLocationGroup()).split(",");
				// master table에 download location이 n건인 경우에 대해 중복제거를 추가함.
				String duplicateRemoveUrl =  String.join(",", Arrays.asList(downloadLocationList)
														.stream()
														.filter(CommonFunction.distinctByKey(p -> p))
														.collect(Collectors.toList()));
				
				if(!isEmpty(duplicateRemoveUrl)) {
					url = duplicateRemoveUrl;
				}
				
				ossNewistData.setDownloadLocation(url);
			}
		}
		
		return ossNewistData;
	}

	@Override
	public Map<String, Object> checkLicenseTextValid(OssMaster bean) {
		String prjId = bean.getPrjId();
		String type = bean.getRegType();
		
		Map<String, Object> result = new HashMap<String, Object>();
		boolean isProd = "REAL".equals(avoidNull(CommonFunction.getProperty("server.mode")));
		String checkFilePath = MessageFormat.format(CommonFunction.emptyCheckProperty("check.license.text.path", "/osc_license_check/{0}_license_check{1}"), (isProd ? "live" : "dev"), "/output");
		File outputDir = new File(checkFilePath);
		
		if(outputDir.exists()) {
			for(File f : outputDir.listFiles()) {
				if(!f.getName().startsWith(prjId)) {
					continue;
				}
				
				// {projectId}_analyze.lock
				if(f.getName().startsWith(prjId) && f.getName().endsWith("lock")) {
					result.put("isValid", false);
					result.put("returnMsg", "It is still being analyzed.");
					
					break;
				}
				
				// {projectId}_result_{timestamp - yyyy-mm-dd_hh:MM:ss} 확장자는 check하지 않음.
				if(!isEmpty(type) && "load".equals(type)) {
					if(f.getName().startsWith(prjId+"_result")) {
						String downloadUrl = CoCodeManager.getCodeExpString(CoConstDef.CD_CHECK_LICENSETEXT_SERVER_INFO, CoConstDef.CD_DOWNLOAD_URL)+f.getName();
						
						if(!isProd) {
							downloadUrl += "&dev=ok";
						}
						
						result.put("isValid", true);
						result.put("returnMsg", "complete");
						result.put("downloadUrl", downloadUrl);
						
						break;
					}
				}
			}
		}
		
		
		if(!result.containsKey("isValid")) {
			result.put("isValid", true);
			result.put("returnMsg", "Success");
		}
		
		return result;
	}
	
	@Override
	public Map<String, Object> startCheckLicenseText(String prjId, String ossReportId) {
		Map<String, Object> resultMap = new HashMap<String, Object>();
		
		try {
			boolean isProd = "REAL".equals(avoidNull(CommonFunction.getProperty("server.mode")));
			List<T2File> binAndroidFileList = fileMapper.getBinAndroidFileList(prjId, ossReportId);
			
			for(T2File binFile : binAndroidFileList) {
				String origFilePath = binFile.getLogiPath();
				
				if(binFile.getLogiPath().endsWith("/")) {
					origFilePath += binFile.getLogiNm();
				} else {
					origFilePath += "/" + binFile.getLogiNm();
				}
				
				String copyFilePath = MessageFormat.format(CommonFunction.emptyCheckProperty("check.license.text.path", "/osc_license_check/{0}_license_check{1}"), (isProd ? "osc" : "dev"), "/output");
				String copyFileName = "";
				
				if(binFile.getOrigNm().endsWith("xml")
						|| binFile.getOrigNm().endsWith("zip")
						|| binFile.getOrigNm().endsWith("tar.gz")) {
					continue; // 이슈로 인해 위의 file들이 들어올 수 있음.
				}
				
				if(binFile.getOrigNm().endsWith("html")) {
					copyFileName = prjId+"_NOTICE.html"; // Notice
				}else if(binFile.getOrigNm().endsWith("xlsx")) {
					copyFileName = prjId+"_OSS-Report.xlsx"; // OSS Report
				}
				
				FileUtil.copyFile(origFilePath, copyFilePath, copyFileName);// OSS Report
				File copiedFile = new File(copyFilePath + "/" + copyFileName);
				copiedFile.setReadable(true, false); // 읽기 권한 부여
				copiedFile.setWritable(true, false); // 쓰기 권한 부여
				copiedFile.setExecutable(true, false); // 실행 권한 부여
				
				log.info(origFilePath + " => " + copyFilePath + "/" + copyFileName + " copy File info");
			}
			
			String EMAIL_VAL = projectMapper.getReviewerEmail(prjId, loginUserName()); // reviewer와 loginUser의 email
			
			resultMap.put("isValid", true);
			resultMap.put("returnMsg", "Success");
			resultMap.put("isProd", isProd);
			resultMap.put("email", EMAIL_VAL);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			
			resultMap.put("isValid", false);
			resultMap.put("returnMsg", "error message");
		}
		
		return resultMap;
	}
	
	@Override
	public Map<String,Object> updateAnalysisComplete(OssAnalysis bean) throws Exception {
		Map<String, Object> resultMap = new HashMap<String, Object>();
	
		int updateCnt = ossMapper.updateAnalysisComplete(bean);
		
		if(updateCnt == 1) {
			resultMap.put("isValid", true);
			resultMap.put("returnMsg", "Success");
		} else {
			throw new Exception("Complete Failure");
		}
		
		return resultMap;
	}

	@Override
	public OssAnalysis getAutoAnalysisSuccessOssInfo(String referenceOssId) {
		return ossMapper.getAutoAnalysisSuccessOssInfo(referenceOssId);
	}
	
	@Override
	public List<ProjectIdentification> checkOssNameData(List<ProjectIdentification> componentData, Map<String, String> validMap, Map<String, String> diffMap){
		List<ProjectIdentification> resultData = new ArrayList<ProjectIdentification>();
		Map<String, Object> ruleMap = T2CoValidationConfig.getInstance().getRuleAllMap();
		
		if(validMap != null) {
			for(String key : validMap.keySet()) {
				if(key.toUpperCase().startsWith("OSSNAME") 
						&& (validMap.get(key).equals(ruleMap.get("OSS_NAME.UNCONFIRMED.MSG")) 
								|| validMap.get(key).equals(ruleMap.get("OSS_NAME.REQUIRED.MSG")))) {
					resultData.addAll((List<ProjectIdentification>) componentData
																	.stream()
																	.filter(e -> key.split("\\.")[1].equals(e.getComponentId())) // 동일한 componentId을 filter
																	.collect(Collectors.toList()));
				}
				
				if(key.toUpperCase().startsWith("OSSVERSION") && validMap.get(key).equals(ruleMap.get("OSS_VERSION.UNCONFIRMED.MSG"))) {
					resultData.addAll((List<ProjectIdentification>) componentData
																	.stream()
																	.filter(e -> key.split("\\.")[1].equals(e.getComponentId())) // 동일한 componentId을 filter
																	.collect(Collectors.toList()));
				}
			}
		}
		
		if(diffMap != null) {
			for(String key : diffMap.keySet()) {
				if(key.toUpperCase().startsWith("OSSNAME") && diffMap.get(key).equals(ruleMap.get("OSS_NAME.UNCONFIRMED.MSG"))) {
					resultData.addAll((List<ProjectIdentification>) componentData
																	.stream()
																	.filter(e -> key.split("\\.")[1].equals(e.getComponentId())) // 동일한 componentId을 filter
																	.collect(Collectors.toList()));
				}
				
				if(key.toUpperCase().startsWith("OSSVERSION") && diffMap.get(key).equals(ruleMap.get("OSS_VERSION.UNCONFIRMED.MSG"))) {
					resultData.addAll((List<ProjectIdentification>) componentData
																	.stream()
																	.filter(e -> key.split("\\.")[1].equals(e.getComponentId())) // 동일한 componentId을 filter
																	.collect(Collectors.toList()));
				}
				
				if(key.toUpperCase().startsWith("DOWNLOADLOCATION") && diffMap.get(key).equals(ruleMap.get("DOWNLOAD_LOCATION.DIFFERENT.MSG"))) {
					int duplicateRow = (int) resultData
												.stream()
												.filter(e -> key.split("\\.")[1].equals(e.getComponentId())) // 동일한 componentId을 filter
												.collect(Collectors.toList())
												.size();
					
					if(duplicateRow == 0) {
						resultData.addAll((List<ProjectIdentification>) componentData
																		.stream()
																		.filter(e -> key.split("\\.")[1].equals(e.getComponentId())) // 동일한 componentId을 filter
																		.collect(Collectors.toList()));
					}
				}
			}
		}
		
		return resultData;
	}
	
	private Map<String, OssMaster> getNewestOssInfoOnTime(OssMaster ossMaster) {
		List<OssMaster> list = ossMapper.getNewestOssInfoByOssMaster(ossMaster);
		return makeBasicOssInfoMap(list, true, false);
	}
}