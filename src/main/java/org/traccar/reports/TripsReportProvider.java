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

        // Add title
        Font titleFont = new Font(Font.FontFamily.HELVETICA, 16, Font.BOLD);
        Paragraph title = new Paragraph("Trips Report", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(20);
        document.add(title);

        // Add date range
        Font dateFont = new Font(Font.FontFamily.HELVETICA, 12);
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
            Font deviceFont = new Font(Font.FontFamily.HELVETICA, 14, Font.BOLD);
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
            String[] headers = {"Start Time", "End Time", "Duration", "Distance",
                    "Average Speed", "Max Speed", "Start Address", "End Address"};
            for (String header : headers) {
                table.addCell(new PdfPCell(new Phrase(header,
                        new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD))));
            }

            // Add trip data
            for (TripReportItem trip : (Collection<TripReportItem>) deviceTrips.getObjects()) {
                table.addCell(new PdfPCell(new Phrase(trip.getStartTime().toString())));
                table.addCell(new PdfPCell(new Phrase(trip.getEndTime().toString())));
                table.addCell(new PdfPCell(new Phrase(String.format("%.2f hours", trip.getDuration() / 3600000.0))));
                table.addCell(new PdfPCell(new Phrase(String.format("%.2f km", trip.getDistance() / 1000.0))));
                table.addCell(new PdfPCell(new Phrase(String.format("%.2f km/h", trip.getAverageSpeed()))));
                table.addCell(new PdfPCell(new Phrase(String.format("%.2f km/h", trip.getMaxSpeed()))));
                table.addCell(new PdfPCell(new Phrase(trip.getStartAddress() != null ? trip.getStartAddress() : "")));
                table.addCell(new PdfPCell(new Phrase(trip.getEndAddress() != null ? trip.getEndAddress() : "")));
            }
            document.add(table);
        }

        document.close();
    }

}
