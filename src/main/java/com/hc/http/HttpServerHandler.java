package com.hc.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;

import io.netty.handler.codec.http.*;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import io.netty.handler.traffic.TrafficCounter;
import io.netty.util.CharsetUtil;
import org.apache.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class HttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger LOG = Logger.getLogger(HttpServerHandler.class);

	private static final String HELLO_PATH = "/hello";
	private static final String REDIRECT_PATH = "/redirect";
	private static final String STATUS_PATH = "/status";

	private static final String HELLO_HTML;
	private static final String STATUS_HTML;
	private static final String PAGE_NOT_FOUND_HTML;
	static {
		// load content of html pages to the memory
		HELLO_HTML = readFileContent("/pages/hello.html");
		STATUS_HTML = readFileContent("/pages/status.html");
		PAGE_NOT_FOUND_HTML = readFileContent("/pages/pageNotFound.html");
	}

	private static final Statistic statistic = Statistic.getInstance();

	/** Contains all URIs processed in current channel */
	private StringBuilder urisInChannel = new StringBuilder();

	/** Channel activation time */
	private Date channelActivationTime;

	private ChannelHandlerContext ctx;
	private FullHttpRequest request;

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		statistic.incrementOpenConnection();
		channelActivationTime = new Date();
		super.channelActive(ctx);
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		statistic.decrementOpenConnection();

		// get and save traffic info
		ChannelHandler trafficHandler = ctx.pipeline().get("trafficHandler");
		ChannelTrafficShapingHandler channelTrafficShapingHandler = (ChannelTrafficShapingHandler) trafficHandler;
		TrafficCounter tc = channelTrafficShapingHandler.trafficCounter();
		double channelLifeDuration = (new Date().getTime() - channelActivationTime.getTime()) / 1000d;
		statistic.addLogRecord(
				new Statistic.LogRecord(
						((InetSocketAddress)ctx.channel().remoteAddress()).getHostString(),
						urisInChannel.toString(),
						channelActivationTime,
						tc.cumulativeWrittenBytes(),
						tc.cumulativeReadBytes(),
						tc.cumulativeWrittenBytes() / channelLifeDuration
				)
		);
		super.channelInactive(ctx);
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
		this.ctx = ctx;
		this.request = request;

		statistic.addRequest(((InetSocketAddress)ctx.channel().remoteAddress()).getHostName());
		urisInChannel.append(request.getUri() + "<br/>");

		if (HttpHeaders.is100ContinueExpected(request)) {
			ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
		}

		if (HELLO_PATH.equals(request.getUri())) {
			sendHello();
		}
		else if (request.getUri().startsWith(REDIRECT_PATH)) {
			sendRedirect();
		}
		else if (STATUS_PATH.equals(request.getUri())) {
			sendStatus();
		}
		else {
			sendNotFound();
		}
	}


	private void sendHello() {
		final int helloDelay = 10;
		ctx.executor().schedule(() -> sendResponse(HELLO_HTML, HttpResponseStatus.OK, "text/html; charset=UTF-8"),
						helloDelay, TimeUnit.SECONDS);
	}

	private void sendRedirect() throws Exception {
		QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.getUri());
		List<String> urlParamValues = queryStringDecoder.parameters().get("url");
		if (urlParamValues != null) {
			String newUri = urlParamValues.get(0);
			statistic.addRedirectUri(newUri);
			FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
			response.headers().set("location", newUri);
			// Close the connection as soon as the message is sent.
			ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
		} else {
			sendNotFound();
		}
	}

	private void sendStatus() throws Exception {
		// parse string to html document
		Document doc = Jsoup.parse(STATUS_HTML);

		Element mainDiv = doc.select("div[id=main]").first();
		mainDiv.prepend("<h3>Unique request count: " + statistic.getUniqueRequestsCount() + "</h3>");
		mainDiv.prepend("<h3>Open connections: " + statistic.getOpenConnections() + "</h3>");
		mainDiv.prepend("<h3>Request count: " + statistic.getRequestCount() + "</h3>");

		Element requestByIpTable = doc.select("table[id=requestByIp]").first();
		for (Map.Entry<String, Statistic.RequestInfo> pair : statistic.getUniqueRequestsMap().entrySet()) {
			requestByIpTable.append(
					"<tr>" +
						"<td>" + pair.getKey() + "</td>" +
						"<td>" + pair.getValue().getCount() + "</td>" +
						"<td>" + pair.getValue().getTime() + "</td>" +
					"</tr>"
			);
		}

		Element redirectCountTable = doc.select("table[id=redirects]").first();
		for (Map.Entry<String, Integer> pair : statistic.getRedirectMap().entrySet()) {
			redirectCountTable.append(
					"<tr>" +
						"<td>" + pair.getKey() + "</td>" +
						"<td>" + pair.getValue() + "</td>" +
					"</tr>");
		}


		Element logTable = doc.select("table[id=log]").first();
		for (Statistic.LogRecord logRecord : statistic.getLogRecords()) {
			logTable.append(
					"<tr>" +
							"<td>" + logRecord.getIp() + "</td>" +
							"<td>" + logRecord.getUri() + "</td>" +
							"<td>" + logRecord.getTs() + "</td>" +
							"<td>" + logRecord.getSentBytes() + "</td>" +
							"<td>" + logRecord.getReceivedBytes() + "</td>" +
							"<td>" + String.format("%.2f", logRecord.getSpeed()) + "</td>" +
					"</tr>"
			);
		}

		sendResponse(doc.html(), HttpResponseStatus.OK, "text/html; charset=UTF-8");
	}

	private void sendNotFound() throws Exception {
		sendResponse(PAGE_NOT_FOUND_HTML, HttpResponseStatus.NOT_FOUND, "text/html; charset=UTF-8");
	}

	private void sendResponse(CharSequence responseStr, HttpResponseStatus httpResponseStatus, String contentType) {
		HttpResponse responseHeaders = new DefaultHttpResponse(request.getProtocolVersion(), httpResponseStatus);
		responseHeaders.headers().set(HttpHeaders.Names.CONTENT_TYPE, contentType);

		ByteBuf responseContent = Unpooled.copiedBuffer(responseStr, CharsetUtil.UTF_8);

		boolean keepAlive = HttpHeaders.isKeepAlive(request);
		if (keepAlive) {
			responseHeaders.headers().set(HttpHeaders.Names.CONTENT_LENGTH, responseContent.readableBytes());
			responseHeaders.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
		}

		// write response
		ctx.write(responseHeaders);
		ctx.write(responseContent);
		ChannelFuture future = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

		// Decide whether to close the connection or not.
		if (!keepAlive) {
			future.addListener(ChannelFutureListener.CLOSE);
		}
	}

	private static String readFileContent(String fileName) {
		String result = null;
		try (InputStream inputStream = HttpServerHandler.class.getResourceAsStream(fileName);
			 BufferedReader fReader = new BufferedReader(new InputStreamReader(inputStream)))
		{
			StringBuilder sb = new StringBuilder();
			while (fReader.ready()) {
				sb.append(fReader.readLine()).append(System.lineSeparator());
			}
			result = sb.toString();
		} catch (Exception e) {
			LOG.error("Exception occurred during loading html from file: " + fileName, e);
		}
		return result;
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		LOG.error("Exception occurred during processing HTTP request/response", cause);
		ctx.close();
	}
}
