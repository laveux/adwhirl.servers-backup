/*
Copyright 2009 AdMob, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package thread;

import com.amazonaws.sdb.AmazonSimpleDB;
import com.amazonaws.sdb.AmazonSimpleDBClient;
import com.amazonaws.sdb.AmazonSimpleDBException;
import com.amazonaws.sdb.model.Attribute;
import com.amazonaws.sdb.model.DeleteAttributesRequest;
import com.amazonaws.sdb.model.Item;
import com.amazonaws.sdb.model.PutAttributesRequest;
import com.amazonaws.sdb.model.ReplaceableAttribute;
import com.amazonaws.sdb.model.SelectRequest;
import com.amazonaws.sdb.model.SelectResponse;
import com.amazonaws.sdb.model.SelectResult;
import com.amazonaws.sdb.util.AmazonSimpleDBUtil;

import java.text.SimpleDateFormat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.UUID;

import org.apache.log4j.Logger;

import util.AdWhirlUtil;

public class RollupDaemon implements Runnable {
	static Logger log = Logger.getLogger("RollupDaemon");
	
	private static AmazonSimpleDB sdb;
	
	public void run() {
		log.info("RollupDaemon started");
		
		sdb = new AmazonSimpleDBClient(AdWhirlUtil.myAccessKey, AdWhirlUtil.mySecretKey, AdWhirlUtil.config);

		//We're a makeshift daemon, let's loop forever
		while(true) {
		    String invalidsNextToken = null;
		    
		    do {
			SelectRequest invalidsRequest = new SelectRequest("select * from `" + AdWhirlUtil.DOMAIN_STATS_INVALID + "`", invalidsNextToken);
			try {
			    SelectResponse invalidsResponse = sdb.select(invalidsRequest);
			    SelectResult invalidsResult = invalidsResponse.getSelectResult();
			    invalidsNextToken = invalidsResult.getNextToken();
			    List<Item> invalidsList = invalidsResult.getItem();
			    
			    String nid = null;
			    String aid = null;
			    
			    for(Item invalidsItem : invalidsList) {								
				nid = invalidsItem.getName();
				
				List<Attribute> attributeList = invalidsItem.getAttribute();
				for(Attribute attribute : attributeList) {
				    if(!attribute.isSetName()) {
					continue;						
				    }
				    
				    String attributeName = attribute.getName();
				    if(attributeName.equals("aid")) {
					if(attribute.isSetValue()) {
					    aid = attribute.getValue();
					}
				    }
				}
				
				rollupNetworkStats(nid, aid);
			    }
			}
			catch(AmazonSimpleDBException e) {
			    log.error("Error querying SimpleDB: " + e.getMessage());
			}
		    }
		    while(invalidsNextToken != null);

		    //TODO: change this
		    try {
			Thread.sleep(60000);
		    } catch (InterruptedException e) {
			log.error("Unable to sleep... continuing");
		    }
		}
	}
	
	private static void rollupNetworkStats(String nid, String aid) {
		if(nid == null || aid == null) {
			log.error("Null parameter passed, nid=\"" + nid + "\" and aid=\"" + aid + "\"");
			return;
		}
		
		String statsNextToken = null;
		
		List<String> sids = new ArrayList<String>();
		int impressions = 0;
		int clicks = 0;
		int type = 0;

		do {
			SelectRequest statsRequest = new SelectRequest("select * from `" + AdWhirlUtil.DOMAIN_STATS_TEMP + "` where `nid` = '" + nid + "'", statsNextToken);
			try {
				SelectResponse statsResponse = sdb.select(statsRequest);
				SelectResult statsResult = statsResponse.getSelectResult();
				statsNextToken = statsResult.getNextToken();
				List<Item> statsList = statsResult.getItem();
	
				String sid = null;
	
				for(Item statsItem : statsList) {	
					sid = statsItem.getName();
					sids.add(sid);
					
					List<Attribute> attributeList = statsItem.getAttribute();
					for(Attribute attribute : attributeList) {
						if(!attribute.isSetName()) {
							continue;						
						}

						String attributeName = attribute.getName();
						if(attributeName.equals("impressions")) {
							if(attribute.isSetValue()) {
								impressions += AmazonSimpleDBUtil.decodeZeroPaddingInt(attribute.getValue());
							}
						}
						else if(attributeName.equals("clicks")) {
							if(attribute.isSetValue()) {
								clicks += AmazonSimpleDBUtil.decodeZeroPaddingInt(attribute.getValue());
							}
						}
						else if(attributeName.equals("type")) {
							if(attribute.isSetValue()) {
								type = AmazonSimpleDBUtil.decodeZeroPaddingInt(attribute.getValue());
							}
						}
					}
				}
			}
			catch(AmazonSimpleDBException e) {
				log.error("Error querying SimpleDB: " + e.getMessage());
				return;
			}
		}
		while(statsNextToken != null);
		
		if(updateStats(nid, aid, impressions, clicks, type)) {
			deleteTempStats(nid, sids);
		}
	}
	
    private static boolean updateStats(String nid, String aid, int impressions, int clicks, int type) {
		Date today = new Date();
		today = startOfDay(today);
		
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
		String dateTime = sdf.format(today);

		String itemName = null;
		
		SelectRequest statsRequest = new SelectRequest("select * from `" + AdWhirlUtil.DOMAIN_STATS + "` where `nid` = '" + nid + "' and `dateTime` = '" + dateTime + "'", null);
		try {
			SelectResponse statsResponse = sdb.select(statsRequest);
			SelectResult statsResult = statsResponse.getSelectResult();
			List<Item> statsList = statsResult.getItem();

			for(Item statsItem : statsList) {	
				itemName = statsItem.getName();
				List<Attribute> attributeList = statsItem.getAttribute();
				for(Attribute attribute : attributeList) {
					if(!attribute.isSetName()) {
						continue;						
					}

					String attributeName = attribute.getName();
					if(attributeName.equals("impressions")) {
						if(attribute.isSetValue()) {
							impressions += AmazonSimpleDBUtil.decodeZeroPaddingInt(attribute.getValue());
						}
					}
					else if(attributeName.equals("clicks")) {
						if(attribute.isSetValue()) {
							clicks += AmazonSimpleDBUtil.decodeZeroPaddingInt(attribute.getValue());
						}
					}
				}
			}			

			log.debug("Pushing: nid <" + nid + ">, aid <" + aid + ">, impressions <" + impressions + ">, clicks <" + clicks + ">");
			List<ReplaceableAttribute> list = new ArrayList<ReplaceableAttribute>();
			list.add(new ReplaceableAttribute("impressions", String.valueOf(impressions), true));
			list.add(new ReplaceableAttribute("clicks", String.valueOf(clicks), true));
			list.add(new ReplaceableAttribute("dateTime", dateTime, true));
			list.add(new ReplaceableAttribute("nid", nid, true));
			list.add(new ReplaceableAttribute("aid", aid, true));
			list.add(new ReplaceableAttribute("type", String.valueOf(type), true));

			if(itemName == null) {
			    itemName = UUID.randomUUID().toString().replace("-", "");
			}
			
			putItem(AdWhirlUtil.DOMAIN_STATS, itemName, list);
		}
		catch(AmazonSimpleDBException e) {
			log.error("Error querying SimpleDB: " + e.getMessage());
			return false;
		}
		
		return true;
	}
	
	private static Date startOfDay(Date date) {
		Calendar calendar = new GregorianCalendar();
		calendar.setTime(date);
		calendar.set(Calendar.HOUR_OF_DAY, 0);
		calendar.set(Calendar.MINUTE, 0);
		calendar.set(Calendar.SECOND, 0);
		calendar.set(Calendar.MILLISECOND, 0);
		return calendar.getTime();
	}
	
	private static void deleteTempStats(String nid, List<String> sids) {
		for(String sid : sids) {
			log.debug("Deleting sid=" + sid);
			DeleteAttributesRequest deleteRequest = new DeleteAttributesRequest(AdWhirlUtil.DOMAIN_STATS_TEMP, sid, null);
			try {
				sdb.deleteAttributes(deleteRequest);
			} catch (AmazonSimpleDBException e) {
				log.error("Error querying SimpleDB: " + e.getMessage());
				return;
			}
		}

		log.debug("Deleting nid=" + nid);
		DeleteAttributesRequest deleteRequest = new DeleteAttributesRequest(AdWhirlUtil.DOMAIN_STATS_INVALID, nid, null);
		try {
			sdb.deleteAttributes(deleteRequest);
		} catch (AmazonSimpleDBException e) {
			log.error("Error querying SimpleDB: " + e.getMessage());
			return;
		}
	}
	
	private static void putItem(String domain, String item, List<ReplaceableAttribute> list) {
		log.debug("Putting Amazon SimpleDB item: " + item);
		PutAttributesRequest request = new PutAttributesRequest().withDomainName(domain).withItemName(item);
		request.setAttribute(list);
		try {
			sdb.putAttributes(request);
		} catch (AmazonSimpleDBException e) {
			log.error("Error querying SimpleDB: " + e.getMessage());
			return;
		}
	}
}
