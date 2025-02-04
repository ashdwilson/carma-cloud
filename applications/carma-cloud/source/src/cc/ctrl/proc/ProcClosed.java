/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package cc.ctrl.proc;

import cc.ctrl.CtrlLineArcs;
import cc.ctrl.TrafCtrl;
import cc.ctrl.TrafCtrlEnums;
import cc.geosrv.Mercator;
import cc.geosrv.xodr.XodrUtil;
import cc.util.Arrays;
import cc.util.FileUtil;
import cc.util.Geo;
import cc.util.MathUtil;
import cc.util.TileUtil;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;

/**
 *
 * @author aaron.cherney
 */
public class ProcClosed extends ProcCtrl
{	
	public ProcClosed(String sLineArcDir)
	{
		super(sLineArcDir);
	}

	@Override
	public void parseMetadata(Path oSource) throws Exception
	{

	}


	@Override
	protected void proc(String sLineArcsFile, double dTol) throws Exception
	{
		try
		{
			ArrayList<TrafCtrl> oCtrls = new ArrayList();
			ArrayList<CtrlLineArcs> oLineArcs = new ArrayList();
			try (DataInputStream oIn = new DataInputStream(new BufferedInputStream(FileUtil.newInputStream(Paths.get(sLineArcsFile)))))
			{
				while (oIn.available() > 0)
				{
					oLineArcs.add(new CtrlLineArcs(oIn));
				}
			}
			ArrayList<int[]> nTiles = new ArrayList();
			for (CtrlLineArcs oCLA : oLineArcs)
			{
				String sType = XodrUtil.getLaneType(oCLA.m_nLaneType);
				TrafCtrl oCtrl = null;
				if (sType.compareTo("roadWorks") == 0)
				{
					oCtrl = new TrafCtrl("closed", "notopen", 0, oCLA.m_dLineArcs, "", true);
				}
				else if (sType.contains("special1"))
				{
					oCtrl = new TrafCtrl("closed", sType.contains("1") ? "taperright" : "taperleft", 0, oCLA.m_dLineArcs, "", true); 
				}
				else if (sType.contains("special2"))
				{
					oCtrl = new TrafCtrl("closed", sType.contains("2") ? "openright" : "openleft", 0, oCLA.m_dLineArcs, "", true);
				}

				if (oCtrl == null)
					continue;
				oCtrls.add(oCtrl);
				oCtrl.write(g_sTrafCtrlDir, g_dExplodeStep, g_nDefaultZoom);
				updateTiles(nTiles, oCtrl.m_oFullGeo.m_oTiles);
			}
			renderTiledData(oCtrls, nTiles);
		}
		catch (Exception oEx)
		{
			oEx.printStackTrace();
		}
	}


	public static void renderTiledData(ArrayList<TrafCtrl> oCtrls, ArrayList<int[]> nTiles) throws IOException
	{
		String[] sEmpty = new String[0];
		TdLayer oLayer = new TdLayer("closed-poly", sEmpty, sEmpty, TdLayer.POLYGON);
		TdLayer oLayerOutline  = new TdLayer("closed-outline", sEmpty, sEmpty, TdLayer.LINESTRING);
		int[] nTags = new int[0];
		ArrayList<TrafCtrl> oTaperCtrls = new ArrayList();
		ArrayList<Integer> oTaperValues = new ArrayList();
		ArrayList<double[]> dTapers = new ArrayList();
		for (String sVal : new String[]{"taperleft", "taperright", "openleft", "openright"})
			oTaperValues.add(TrafCtrlEnums.getCtrlVal("closed", sVal));
		
		Collections.sort(oTaperValues);
		int nCtrlIndex = oCtrls.size();
		while (nCtrlIndex-- > 0)
		{
			TrafCtrl oCtrl = oCtrls.get(nCtrlIndex);
			int nCtrlVal = MathUtil.bytesToInt(oCtrl.m_yControlValue);
			if (Collections.binarySearch(oTaperValues, nCtrlVal) >= 0)
			{
				oCtrls.remove(nCtrlIndex);
				oTaperCtrls.add(oCtrl);
				String sCtrlVal = TrafCtrlEnums.CTRLS[oCtrl.m_nControlType][nCtrlVal];
				int nPoints = (int)(oCtrl.m_oFullGeo.m_dLength / 0.2) + 1;
				double dPercent = 1.0 / nPoints;
				int nCount = nPoints;
				int nIncrease = -1;
				if (sCtrlVal.contains("taper"))
				{
					nCount = 0;
					nIncrease = 1;
				}
				
				double[] dC = oCtrl.m_oFullGeo.m_dC;
				boolean bRight = sCtrlVal.contains(("right"));
				double[] dStraight = bRight ? oCtrl.m_oFullGeo.m_dNT : oCtrl.m_oFullGeo.m_dPT;
				int nLimit = Arrays.size(dC) - 2;
				double dDist = 0.0;
				double[] dTaper = Arrays.newDoubleArray(nPoints * 2);
				dTaper = Arrays.add(dTaper, dStraight[1], dStraight[2]);
				for (int nIndex = 1; nIndex < nLimit; nIndex += 2)
				{
					double dX1 = dC[nIndex];
					double dY1 = dC[nIndex + 1];
					double dX2 = dC[nIndex + 2];
					double dY2 = dC[nIndex + 3];
					dDist += Geo.distance(dX1, dY1, dX2, dY2);
					if (dDist >= 0.2)
					{
						dDist = 0.0;
						double dX3 = dStraight[nIndex];
						double dY3 = dStraight[nIndex + 1];
						double dHeading = Geo.heading(dX3, dY3, dX1, dY1);
						nCount += nIncrease;
						double dOffset = Geo.distance(dX3, dY3, dX1, dY1) * 2 * dPercent * nCount;
						dTaper = Arrays.add(dTaper, dX3 + Math.cos(dHeading) * dOffset, dY3 + Math.sin(dHeading) * dOffset);
					}
				}
				if (bRight)
				{
					dTapers.add(dTaper);
					dTapers.add(dStraight);
				}
				else
				{
					dTapers.add(dStraight);
					dTapers.add(dTaper);
				}
			}
		}
		for (int[] nTile : nTiles)
		{
			int nX = nTile[0];
			int nY = nTile[1];
			Files.createDirectories(Paths.get(String.format(g_sTdFileFormat, nX, 0, 0, 0)).getParent(), FileUtil.DIRPERS);
			writeIndexFile(oCtrls, nX, nY);
			writeIndexFile(oTaperCtrls, nX, nY);
			oLayer.clear();
			oLayerOutline.clear();
			int nTaperCnt = 0;
			ArrayList<ArrayList<double[]>> oClippedLanes = new ArrayList();
			double[] dClipBounds = TileUtil.getClippingBounds(g_nDefaultZoom, nX, nY);
			double[][] dClips = new double[][]{Arrays.newDoubleArray(512), Arrays.newDoubleArray(512), Arrays.newDoubleArray(512)};
			oClippedLanes.add(new ArrayList());
			oClippedLanes.add(new ArrayList());
			oClippedLanes.add(new ArrayList());
			for (TrafCtrl oCtrl : oCtrls)
			{
				if (Collections.binarySearch(oCtrl.m_oFullGeo.m_oTiles, nTile, Mercator.TILECOMP) < 0)
					continue;
				for (ArrayList oClipped : oClippedLanes)
					oClipped.clear();
				TileUtil.clipCtrlGeoForTile(oCtrl.m_oFullGeo, dClips, dClipBounds, oClippedLanes);
				int nParts = oClippedLanes.get(0).size();
				for (int nPart = 0; nPart < nParts; nPart++)
				{
					double[] dNT = oClippedLanes.get(1).get(nPart);
					double[] dPT = oClippedLanes.get(2).get(nPart);

					double[] dClippedPoly = Geo.createPolygon(dPT, dNT);
					if (!Geo.isClockwise(dClippedPoly, 1))
					{
						dClippedPoly = Geo.reverseOrder(dClippedPoly);
					}

					oLayer.add(new TdFeature(dClippedPoly, nTags, oCtrl));
					dClippedPoly = Arrays.add(dClippedPoly, dClippedPoly[1], dClippedPoly[2]);
					oLayerOutline.add(new TdFeature(dClippedPoly, nTags, oCtrl));
				}
			}
			
			for (TrafCtrl oCtrl : oTaperCtrls)
			{
				if (Collections.binarySearch(oCtrl.m_oFullGeo.m_oTiles, nTile, Mercator.TILECOMP) < 0)
				{
					nTaperCnt += 2;
					continue;
				}
				
				double[] dPoly = Geo.createPolygon(dTapers.get(nTaperCnt++), dTapers.get(nTaperCnt++));

				oLayer.add(new TdFeature(dPoly, nTags, oCtrl));
				dPoly = Arrays.add(dPoly, dPoly[1], dPoly[2]);
				oLayerOutline.add(new TdFeature(dPoly, nTags, oCtrl));
			}
			
			

			if (!oLayer.isEmpty())
			{
				try (DataOutputStream oOut = new DataOutputStream(new BufferedOutputStream(FileUtil.newOutputStream(Paths.get(String.format(g_sTdFileFormat, nX, g_nDefaultZoom, nX, nY)), FileUtil.APPENDTO, FileUtil.FILEPERS))))
				{
					oLayer.write(oOut);
					oLayerOutline.write(oOut);
				}
			}
		}
	}


	@Override
	public ArrayList<CtrlLineArcs> combine(ArrayList<CtrlLineArcs> oLanes, double dTol)
	{
		return oLanes;
	}
}