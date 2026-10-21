"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.proxy = void 0;
const cookie = __importStar(require("cookie"));
const http_proxy_1 = __importDefault(require("http-proxy"));
const http_1 = require("../common/http");
exports.proxy = http_proxy_1.default.createProxyServer({});
// The error handler catches when the proxy fails to connect (for example when
// there is nothing running on the target port).
exports.proxy.on("error", (error, _, res) => {
    // This could be for either a web socket or a regular request.  Despite what
    // the types say, writeHead() will not exist on web socket requests (nor will
    // status() from Express).  But writing out the code manually does not work
    // for regular requests thus the branching behavior.
    if (typeof res.writeHead !== "undefined") {
        res.writeHead(http_1.HttpCode.ServerError);
        res.end(error.message);
    }
    else {
        res.end(`HTTP/1.1 ${http_1.HttpCode.ServerError} ${error.message}\r\n\r\n`);
    }
});
function identity(val) {
    return val;
}
// Strip the code-server cookie if it exists to avoid transmitting the cookie
// to potentially malicious local ports.
exports.proxy.on("proxyReq", (preq, req) => {
    if (req.headers.cookie) {
        const cookieSessionName = (0, http_1.getCookieSessionName)(req.args["cookie-suffix"]);
        // Encoding and decoding are no-ops; we just want to remove the token
        // without changing anything else about the cookies because not all
        // applications encode/decode the same way `cookie` here does.
        preq.setHeader("Cookie", cookie.stringifyCookie(Object.assign(Object.assign({}, cookie.parseCookie(req.headers.cookie, { decode: identity })), { [cookieSessionName]: undefined }), {
            encode: identity,
        }));
    }
});
// Intercept the response to rewrite absolute redirects against the base path.
// Is disabled when the request has no base path which means /absproxy is in use.
exports.proxy.on("proxyRes", (res, req) => {
    if (res.headers.location && res.headers.location.startsWith("/") && req.base) {
        res.headers.location = req.base + res.headers.location;
    }
});
//# sourceMappingURL=proxy.js.map