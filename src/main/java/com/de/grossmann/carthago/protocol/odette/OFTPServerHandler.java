package com.de.grossmann.carthago.protocol.odette;

import com.de.grossmann.carthago.protocol.odette.config.OFTPSessionConfiguration;
import com.de.grossmann.carthago.protocol.odette.data.commands.*;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class OFTPServerHandler extends ChannelInboundHandlerAdapter
{

    private static final Logger LOGGER;

    private final OFTPSessionConfiguration oftpSessionConfigurationDefaults;

    private final static AttributeKey<Long>                     DATA_PDU_COUNTER_KEY          = AttributeKey
        .newInstance("receivedDataPdus");
    private final static AttributeKey<OFTPSessionConfiguration> OFTP_SESSION_CONFIGURTION_KEY = AttributeKey
        .newInstance("oftpSessionConfiguration");
    private final static  AttributeKey<Long>                    TIMER_KEY                     = AttributeKey
        .newInstance("timer");

    static
    {
        LOGGER = LoggerFactory.getLogger(OFTPServerHandler.class);
    }

    OFTPServerHandler(final OFTPSessionConfiguration oftpSessionConfiguration)
    {
        this.oftpSessionConfigurationDefaults = oftpSessionConfiguration;
    }

    /**
     * Calls {@link ChannelHandlerContext#fireChannelActive()} to forward
     * to the next {@link io.netty.channel.ChannelHandler} in the {@link io.netty.channel.ChannelPipeline}.
     * <p/>
     *
     * @param ctx the {@link ChannelHandlerContext} which this {@link io.netty.handler.codec.ByteToMessageDecoder} belongs to
     */
    @Override public void channelActive(ChannelHandlerContext ctx) throws Exception
    {
        LOGGER.debug("Channel {} activated. {} -> {}", ctx.channel().id(), ctx.channel().localAddress(),
                     ctx.channel().remoteAddress());

        send(ctx, new SSRM());

        super.channelActive(ctx);
    }

    /**
     * Calls {@link ChannelHandlerContext#fireChannelRead(Object)} to forward
     * to the next {@link io.netty.channel.ChannelHandler} in the {@link io.netty.channel.ChannelPipeline}.
     * <p/>
     *
     * @param ctx the {@link ChannelHandlerContext} which this {@link io.netty.handler.codec.ByteToMessageDecoder} belongs to
     * @param msg the {@link Object} which holds the decoded message
     */
    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception
    {
        Command response = handleCommand(ctx, (Command) msg);
        if (response != null)
        {
            send(ctx, response);
        }
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception
    {
        LOGGER.warn("Unexpected exception from downstream. " + cause.getMessage());
        ctx.close();
    }

    private void send(final ChannelHandlerContext ctx, final Command command)
    {
        ctx.writeAndFlush(command);
    }

    private Command handleCommand(final ChannelHandlerContext ctx, final Command command)
    {
        Command response = null;

        if (command instanceof SSID)
        {
            final SSID incomingSSID = (SSID) command;
            final SSID outgoingSSID = new SSID();

            if (incomingSSID.getSsidlev() != 5)
            {
                ESID esid = new ESID();
                esid.setEsidreas(10);

                response = esid;
            }
            else
            {
                OFTPSessionConfiguration oftpSessionConfiguration = negotiate(
                    this.oftpSessionConfigurationDefaults, incomingSSID);

                ctx.channel().attr(OFTP_SESSION_CONFIGURTION_KEY).set(oftpSessionConfiguration);

                outgoingSSID.setSsidlev(oftpSessionConfiguration.getLevel());
                outgoingSSID.setSsidcode(oftpSessionConfiguration.getUserCode());
                outgoingSSID.setSsidpswd(oftpSessionConfiguration.getPassword());
                outgoingSSID.setSsidsdeb(oftpSessionConfiguration.getDataExchangeBufferSize());
                outgoingSSID.setSsidsr(String.valueOf(oftpSessionConfiguration.getCapabilities()));
                outgoingSSID.setSsidcmpr(String.valueOf(oftpSessionConfiguration.getCompression()));
                outgoingSSID.setSsidrest(String.valueOf(oftpSessionConfiguration.getRestart()));
                outgoingSSID.setSsidspec(String.valueOf(oftpSessionConfiguration.getSpecialLogic()));
                outgoingSSID.setSsidcred(oftpSessionConfiguration.getCredit());
                outgoingSSID.setSsidauth(String.valueOf(oftpSessionConfiguration.getAuthentication()));
                outgoingSSID.setSsidrsv1(oftpSessionConfiguration.getReserved());
                outgoingSSID.setSsiduser(oftpSessionConfiguration.getUserData());

                response = outgoingSSID;

            }
        }
        else if (command instanceof SFID)
        {
            ctx.channel().attr(TIMER_KEY).set(System.currentTimeMillis());
            response = new SFPA();
            ((SFPA) response).setSfpacnt(0L);
        }
        else if (command instanceof DATA)
        {
            OFTPSessionConfiguration oftpSessionConfiguration = ctx.channel().attr(OFTP_SESSION_CONFIGURTION_KEY).get();

            Long receivedDataPdus = ctx.channel().attr(DATA_PDU_COUNTER_KEY).get();

            if (receivedDataPdus == null || receivedDataPdus.equals(0L))
            {
                receivedDataPdus = 1L;
            }
            else
            {
                receivedDataPdus++;
            }

            if (receivedDataPdus.equals(oftpSessionConfiguration.getCredit()))
            {
                response = new CDT();
                receivedDataPdus = 0L;
            }

            ctx.channel().attr(DATA_PDU_COUNTER_KEY).set(receivedDataPdus);
        }
        else if (command instanceof EFID)
        {
            Long start = ctx.channel().attr(TIMER_KEY).get();
            Long end   = System.currentTimeMillis();

            System.out.printf("transmission took %d ms%n",
                              (end - start));

            response = new EFPA();
            ((EFPA) response).setEfpacd("N");
        }
        else if (command instanceof CD)
        {
            String txt = "DUMMY";

            response = new ESID();
            ((ESID) response).setEsidreas(0);
            ((ESID) response).setEsidreasl(txt.length());
            ((ESID) response).setEsidreast(txt);

        }
        else if (command instanceof ESID)
        {
            ctx.close();
        }

        return response;
    }

    private OFTPSessionConfiguration negotiate(OFTPSessionConfiguration oftpSessionConfigurationDefaults, SSID incomingSSID)
    {
        OFTPSessionConfiguration oftpSessionConfiguration = new OFTPSessionConfiguration(
            oftpSessionConfigurationDefaults.getUserCode(), oftpSessionConfigurationDefaults.getPassword());

        if (incomingSSID.getSsidlev() < oftpSessionConfigurationDefaults.getLevel())
        {
            oftpSessionConfiguration.setLevel(incomingSSID.getSsidlev());
        }
        else
        {
            oftpSessionConfiguration.setLevel(oftpSessionConfigurationDefaults.getLevel());
        }

        if (incomingSSID.getSsidsdeb() < oftpSessionConfigurationDefaults.getDataExchangeBufferSize())
        {
            oftpSessionConfiguration.setDataExchangeBufferSize(incomingSSID.getSsidsdeb());
        }
        else
        {
            oftpSessionConfiguration
                .setDataExchangeBufferSize(oftpSessionConfigurationDefaults.getDataExchangeBufferSize());
        }

        if (incomingSSID.getSsidcred() < oftpSessionConfigurationDefaults.getCredit())
        {
            oftpSessionConfiguration.setCredit(incomingSSID.getSsidcred());
        }
        else
        {
            oftpSessionConfiguration.setCredit(oftpSessionConfigurationDefaults.getCredit());
        }

        return oftpSessionConfiguration;
    }
}
