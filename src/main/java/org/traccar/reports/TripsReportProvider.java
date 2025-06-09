/*
 * Copyright 2016 - 2022 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 Andrey Kunitsyn (andrey@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.reports;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;

import org.traccar.config.Config;
import org.traccar.helper.model.DeviceUtil;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.reports.common.ReportUtils;
import org.traccar.reports.model.DeviceReportSection;
import org.traccar.reports.model.TripReportItem;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;

import jakarta.inject.Inject;

public class TripsReportProvider {

    private final Config config;
    private final ReportUtils reportUtils;
    private final Storage storage;

    @Inject
    public TripsReportProvider(Config config, ReportUtils reportUtils, Storage storage) {
        this.config = config;
        this.reportUtils = reportUtils;
        this.storage = storage;
    }

    public Collection<TripReportItem> getObjects(
            long userId, Collection<Long> deviceIds, Collection<Long> groupIds,
            Date from, Date to) throws StorageException {
        reportUtils.checkPeriodLimit(from, to);

        ArrayList<TripReportItem> result = new ArrayList<>();
        for (Device device: DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, groupIds)) {
            result.addAll(reportUtils.detectTripsAndStops(device, from, to, TripReportItem.class));
        }
        return result;
    }

    public void getExcel(OutputStream outputStream,
            long userId, Collection<Long> deviceIds, Collection<Long> groupIds,
            Date from, Date to) throws StorageException, IOException, DocumentException {
        reportUtils.checkPeriodLimit(from, to);

        ArrayList<DeviceReportSection> devicesTrips = new ArrayList<>();
        for (Device device: DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, groupIds)) {
            Collection<TripReportItem> trips = reportUtils.detectTripsAndStops(device, from, to, TripReportItem.class);
            DeviceReportSection deviceTrips = new DeviceReportSection();
            deviceTrips.setDeviceName(device.getName());
            if (device.getGroupId() > 0) {
                Group group = storage.getObject(Group.class, new Request(
                        new Columns.All(), new Condition.Equals("id", device.getGroupId())));
                if (group != null) {
                    deviceTrips.setGroupName(group.getName());
                }
            }
            deviceTrips.setObjects(trips);
            devicesTrips.add(deviceTrips);
        }

        Document document = new Document(PageSize.A4.rotate());
        PdfWriter.getInstance(document, outputStream);
        document.open();

        // Create BaseFont for Arabic support
        BaseFont arabicFont;
        try {
            // Try to use Arial Unicode MS which supports Arabic
            arabicFont = BaseFont.createFont("Arial Unicode MS", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        } catch (Exception e) {
            try {
                // Fallback to built-in Helvetica with Identity-H encoding for better Unicode support
                arabicFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.IDENTITY_H, BaseFont.NOT_EMBEDDED);
            } catch (Exception ex) {
                // Final fallback to default font
                arabicFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            }
        }

        // Create fonts with Arabic support
        Font titleFont = new Font(arabicFont, 16, Font.BOLD);
        Font dateFont = new Font(arabicFont, 12);
        Font deviceFont = new Font(arabicFont, 14, Font.BOLD);
        Font tableHeaderFont = new Font(arabicFont, 10, Font.BOLD);
        Font tableCellFont = new Font(arabicFont, 10);

        // Add title
        Paragraph title = new Paragraph("Trips Report", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(20);
        document.add(title);

        // Add date range
        Paragraph dateRange = new Paragraph(
            String.format("From: %s to %s", from, to),
            dateFont
        );
        dateRange.setAlignment(Element.ALIGN_CENTER);
        dateRange.setSpacingAfter(20);
        document.add(dateRange);

        // Add trips data
        for (DeviceReportSection deviceTrips : devicesTrips) {
            // Add device section
            String deviceName = deviceTrips.getDeviceName();
            if (deviceTrips.getGroupName() != null) {
                deviceName += " (" + deviceTrips.getGroupName() + ")";
            }
            Paragraph deviceTitle = new Paragraph(deviceName, deviceFont);
            deviceTitle.setSpacingBefore(20);
            deviceTitle.setSpacingAfter(10);
            document.add(deviceTitle);

            // Create table for trips
            PdfPTable table = new PdfPTable(8);
            table.setWidthPercentage(100);

            // Add table headers
            String[] headers = {
                "Heure de début", "Heure de fin", "Durée", "Distance",
                "Vitesse moyenne", "Vitesse max", "Adresse de départ", "Adresse d'arrivée"
            };
            for (String header : headers) {
                table.addCell(new PdfPCell(new Phrase(header, tableHeaderFont)));
            }

            // Add trip data
            for (TripReportItem trip : (Collection<TripReportItem>) deviceTrips.getObjects()) {
                table.addCell(new PdfPCell(new Phrase(trip.getStartTime().toString(), tableCellFont)));
                table.addCell(new PdfPCell(new Phrase(trip.getEndTime().toString(), tableCellFont)));
                table.addCell(new PdfPCell(new Phrase(
                        String.format("%.2f hours", trip.getDuration() / 3600000.0), tableCellFont)));
                table.addCell(new PdfPCell(new Phrase(
                        String.format("%.2f km", trip.getDistance() / 1000.0), tableCellFont)));
                table.addCell(new PdfPCell(new Phrase(
                        String.format("%.2f km/h", trip.getAverageSpeed()), tableCellFont)));
                table.addCell(new PdfPCell(new Phrase(
                        String.format("%.2f km/h", trip.getMaxSpeed()), tableCellFont)));
                table.addCell(new PdfPCell(new Phrase(
                        trip.getStartAddress() != null ? trip.getStartAddress() : "", tableCellFont)));
                table.addCell(new PdfPCell(new Phrase(
                        trip.getEndAddress() != null ? trip.getEndAddress() : "", tableCellFont)));
            }
            document.add(table);
        }

        document.close();
    }

}
