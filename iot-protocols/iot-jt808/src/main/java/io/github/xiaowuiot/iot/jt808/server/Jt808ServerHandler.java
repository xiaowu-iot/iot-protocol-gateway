package io.github.xiaowuiot.iot.jt808.server;

import io.github.xiaowuiot.iot.codec.session.Session;
import io.github.xiaowuiot.iot.codec.session.SessionManager;
import io.github.xiaowuiot.iot.codec.util.RateLimitedLog;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.github.xiaowuiot.iot.jt808.Jt808Message;
import io.github.xiaowuiot.iot.jt808.MessageId;
import io.github.xiaowuiot.iot.jt808.body.Auth;
import io.github.xiaowuiot.iot.jt808.body.GeneralResponse;
import io.github.xiaowuiot.iot.jt808.body.Multimedia;
import io.github.xiaowuiot.iot.jt808.body.Register;
import io.github.xiaowuiot.iot.jt808.body.RegisterResponse;
import io.github.xiaowuiot.iot.jt808.codec.BodyDecoders;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 平台端的 808 业务处理：注册、鉴权、心跳、应答，其他业务数据交给 {@link Jt808MessageListener}。
 * <p>
 * 只有鉴权成功的连接才有会话；没鉴权就发业务数据的，回失败应答，数据不处理。
 * <p>
 * 本身无状态（状态都在 {@link Session} 和 Channel 属性里），所有连接共用一个实例。
 */
@ChannelHandler.Sharable
public class Jt808ServerHandler extends SimpleChannelInboundHandler<Jt808Message> {

    private static final Logger log = LoggerFactory.getLogger(Jt808ServerHandler.class);

    private static final RateLimitedLog FAILURE_LOG = new RateLimitedLog(log, 1000);

    /**
     * 鉴权之前还没有会话，注册应答等消息的流水号先记在连接上
     */
    private static final AttributeKey<AtomicInteger> PRE_AUTH_SERIAL = AttributeKey.valueOf("jt808.preAuthSerial");

    private final SessionManager sessions;
    private final Jt808AuthService authService;
    private final Jt808CommandSender commandSender;
    private final Jt808MessageListener listener;

    public Jt808ServerHandler(SessionManager sessions, Jt808AuthService authService,
                              Jt808CommandSender commandSender, Jt808MessageListener listener) {
        this.sessions = sessions;
        this.authService = authService;
        this.commandSender = commandSender;
        this.listener = listener;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Jt808Message msg) {
        Jt808Header header = msg.getHeader();
        Session session = SessionManager.get(ctx.channel());
        if (session != null) {
            session.touch();
        }
        if (msg.isFragment()) {
            // 分包还没收齐，每一包都要应答，否则终端会重发这一包
            reply(ctx, header, GeneralResponse.SUCCESS);
            return;
        }
        switch (header.getMsgId()) {
            case MessageId.REGISTER:
                onRegister(ctx, header, msg.<Register>getBody());
                return;
            case MessageId.AUTH:
                onAuth(ctx, header, msg.<Auth>getBody());
                return;
            default:
                break;
        }
        if (session == null) {
            log.warn("{} 未鉴权，拒绝处理 {}", ctx.channel(), header);
            reply(ctx, header, GeneralResponse.FAILURE);
            return;
        }
        switch (header.getMsgId()) {
            case MessageId.HEARTBEAT:
                reply(ctx, header, GeneralResponse.SUCCESS);
                break;
            case MessageId.LOGOUT:
                // 注销后终端需要重新注册，先应答再断开
                reply(ctx, header, GeneralResponse.SUCCESS).addListener(ChannelFutureListener.CLOSE);
                break;
            case MessageId.TERMINAL_GENERAL_RESPONSE:
            case MessageId.QUERY_PARAMS_RESPONSE:
            case MessageId.LOCATION_QUERY_RESPONSE:
            case MessageId.ATTRIBUTES_RESPONSE:
            case MessageId.CAMERA_RESPONSE:
                // 终端对平台指令的应答，不需要再回应答
                if (!commandSender.onResponse(session, msg)) {
                    log.debug("{} 收到无人等待的应答 {}", session, msg);
                }
                break;
            case MessageId.QUESTION_ANSWER:
            case MessageId.VEHICLE_CONTROL_RESPONSE:
            case MessageId.RECORDER_UPLOAD:
                // 应答里带着业务数据（司机选的答案、记录仪数据）：既要交给等应答的调用方，也要交给业务处理，但不回应答
                commandSender.onResponse(session, msg);
                notifyListener(session, msg);
                break;
            case MessageId.DRIVER_INFO:
                // 0x0702 可能是 0x8702 的应答，也可能是插拔卡时主动上报：两种情况都按业务消息处理并回通用应答
                commandSender.onResponse(session, msg);
                onBusinessMessage(ctx, session, msg);
                break;
            default:
                onBusinessMessage(ctx, session, msg);
        }
    }

    private void onRegister(ChannelHandlerContext ctx, Jt808Header header, Register register) {
        Jt808AuthService.RegisterResult r = authService.register(header, register);
        log.info("{} 终端注册 {} {}，结果 {}", ctx.channel(), header.deviceId(), register, r.getResult());
        Jt808Header h = Jt808Header.to(header, MessageId.REGISTER_RESPONSE, preAuthSerial(ctx.channel()));
        ctx.writeAndFlush(new Jt808Message(h, new RegisterResponse(header.getSerialNo(), r.getResult(), r.getAuthCode())));
    }

    private void onAuth(ChannelHandlerContext ctx, Jt808Header header, Auth auth) {
        if (!authService.authenticate(header, auth)) {
            log.warn("{} 终端 {} 鉴权失败，鉴权码 {}", ctx.channel(), header.deviceId(), auth.getAuthCode());
            reply(ctx, header, GeneralResponse.FAILURE);
            return;
        }
        Session session = sessions.register(header.deviceId(), Jt808CommandSender.PROTOCOL, ctx.channel());
        session.setAttribute(Jt808CommandSender.HEADER_TEMPLATE, Jt808Header.to(header, 0, 0));
        reply(ctx, header, GeneralResponse.SUCCESS);
    }

    private void notifyListener(Session session, Jt808Message msg) {
        try {
            listener.onMessage(session, msg);
        } catch (RuntimeException e) {
            FAILURE_LOG.warn("业务处理抛出异常", session + " " + msg.getHeader(), e);
        }
    }

    private void onBusinessMessage(ChannelHandlerContext ctx, Session session, Jt808Message msg) {
        Jt808Header header = msg.getHeader();
        boolean supported = BodyDecoders.get(header.getMsgId()) != null;
        CompletionStage<Void> result;
        try {
            result = listener.onMessage(session, msg);
        } catch (RuntimeException e) {
            // 业务代码的 bug（比如类型转换错误）不能让设备掉线：当作处理失败，不回应答
            FAILURE_LOG.warn("业务处理抛出异常，不回应答", session + " " + header, e);
            return;
        }
        result.whenComplete((v, e) -> {
            if (e == null && msg.getBody() instanceof Multimedia.Data) {
                // 多媒体数据收齐后用专门的应答 0x8800，不缺包时重传列表为空
                long mediaId = msg.<Multimedia.Data>getBody().getMediaId();
                Jt808Header h = Jt808Header.to(header, MessageId.MULTIMEDIA_RESPONSE, session.nextSerialNo());
                ctx.writeAndFlush(new Jt808Message(h, new Multimedia.DataResponse(mediaId, Collections.<Integer>emptyList())));
            } else if (e == null) {
                reply(ctx, header, supported ? GeneralResponse.SUCCESS : GeneralResponse.UNSUPPORTED);
            } else {
                // 不回应答，终端会重发。下游故障时每条消息都会走到这里，日志必须限流
                FAILURE_LOG.warn("处理失败，不回应答，等终端重发", session + " " + header, e);
            }
        });
    }

    private ChannelFuture reply(ChannelHandlerContext ctx, Jt808Header req, int result) {
        Session session = SessionManager.get(ctx.channel());
        int serial = session != null ? session.nextSerialNo() : preAuthSerial(ctx.channel());
        Jt808Header h = Jt808Header.to(req, MessageId.PLATFORM_GENERAL_RESPONSE, serial);
        return ctx.writeAndFlush(new Jt808Message(h, new GeneralResponse(req.getSerialNo(), req.getMsgId(), result)));
    }

    private static int preAuthSerial(Channel channel) {
        AtomicInteger counter = channel.attr(PRE_AUTH_SERIAL).get();
        if (counter == null) {
            counter = new AtomicInteger();
            AtomicInteger old = channel.attr(PRE_AUTH_SERIAL).setIfAbsent(counter);
            if (old != null) {
                counter = old;
            }
        }
        return counter.getAndIncrement() & 0xFFFF;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.debug("{} 新连接", ctx.channel());
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        sessions.unregister(ctx.channel());
        ctx.fireChannelInactive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            log.info("{} 长时间没有收到数据，判定离线", ctx.channel());
            ctx.close();
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 走到这里的基本都是网络异常（比如 Connection reset），解析错误在解码器里已经处理掉了
        log.info("{} 连接异常，关闭：{}", ctx.channel(), cause.toString());
        ctx.close();
    }
}
