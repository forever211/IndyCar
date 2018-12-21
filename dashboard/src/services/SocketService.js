import openSocket from "socket.io-client";

export class SocketService {

    static instance;

    constructor(host, port) {
        if (SocketService.instance) {
            return SocketService.instance;
        }

        SocketService.instance = this;

        this.host = host;
        this.port = port;
        this.socket = undefined;
    }

    start = (cb) => {
        this.socket = openSocket(`${this.host}:${this.port}`, {
            reconnection: true,
            reconnectionDelay: 1000,
            reconnectionDelayMax: 5000,
            reconnectionAttempts: 99999
        });

        this.socket.on('connect', () => {
            console.log("Connected to server", this.socket);
            cb();
        });
    };

    send = (event, msg) => {
        this.socket.emit(event, msg);
    };

    subscribe = (event, cb) => {
        this.socket.on(event, cb);
    };

    unsubscribe = (event, cb) => {
        this.socket.off(event, cb);
    };
}