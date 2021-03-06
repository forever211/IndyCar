import React from "react";
import {Card} from "@blueprintjs/core";
import "./SpeedDataComponent.css";
import {Icon} from "@blueprintjs/core/lib/esm/components/icon/icon";
import {Line} from "react-chartjs-2";
import CarInformationService, {CAR_LAP_LISTENER} from "../../services/CarInformationService";
import PropTypes from 'prop-types';

/**
 * @author Chathura Widanage
 */
export default class SpeedDataComponent extends React.Component {

    constructor(props) {
        super(props);
        this.state = {
            x: [],
            carInfo: {},
            lapRecords: {},
            driverImage: `url(img/drivers/no.jpg)`
        };

        // for (let i = 0; i < 18; i++) {
        //     this.state.x.push(Math.floor(Math.random() * 250))
        // }
    }

    updateCarInformation = (props = this.props) => {
        //get car information
        let carInfo = CarInformationService.getCarInformation(props.carNumber);

        let rawImgUrl = `img/drivers/${carInfo.carNumber}.jpg`;
        let imageUrl = `url(img/drivers/no.jpg)`;

        this.imageExists(rawImgUrl, (exists) => {
            if (exists) {
                imageUrl = `url(img/drivers/${carInfo.carNumber}.jpg)`;
            }
            this.setState({
                carInfo,
                driverImage: imageUrl
            });
        });
    };

    onLapRecordReceived = (lapRecord) => {
        if (lapRecord.carNumber === this.props.carNumber) {
            let lapRecords = this.state.lapRecords;
            if (!lapRecords[lapRecord.completedLaps]) {
                lapRecords[lapRecord.completedLaps] = lapRecord.time;
                this.setState({
                    lapRecords
                });
            }
        }
    };

    componentDidMount() {
        this.updateCarInformation();


        let lapTimes = CarInformationService.getLapTimes();
        Object.values(lapTimes).forEach(records => {
            records.forEach(this.onLapRecordReceived);
        });


        CarInformationService.addEventListener(CAR_LAP_LISTENER, this.onLapRecordReceived);
    }

    componentWillUnmount() {
        CarInformationService.removeEventListener(CAR_LAP_LISTENER, this.onLapRecordReceived);
    }


    componentWillReceiveProps(nextProps) {
        if (nextProps.carNumber !== this.props.carNumber) {
            this.updateCarInformation(nextProps);
        }
    }

    imageExists = (image_url, cb) => {
        let xhr = new XMLHttpRequest();

        xhr.open('HEAD', image_url, true);
        xhr.onload = function (e) {
            cb(xhr.status !== 404)
        };
        xhr.onerror = function (e) {
            cb(false)
        };

        xhr.send();
    };

    render() {

        let lapRecs = Object.keys(this.state.lapRecords).map(key => {
            return {lap: key, time: this.state.lapRecords[key]}
        });

        let sortedLapNumbers = [];
        let sortedLaps = lapRecs.sort((a, b) => {
            let lapA = parseInt(a.lap, 10);
            let lapB = parseInt(b.lap, 10);
            if (lapA < lapB) {
                return -1;
            } else if (lapA > lapB) {
                return 1;
            }
            return 0;
        }).map(lapR => {
            sortedLapNumbers.push(lapR.lap);
            return lapR.time;
        });

        return (
            <Card className="speed-data-component">
                <div className="speed-data-rank-wrapper">
                    <div className="speed-data-info-wrapper">
                        <div className="speed-data-car-info">
                            <div className="speed-data-car-info-middle">
                                <div className="speed-data-car-info-number"
                                     style={{backgroundImage: this.state.driverImage}}>
                                    <span>
                                    {this.state.carInfo.carNumber}
                                    </span>
                                </div>
                                <div className="speed-data-car-info-engine">
                                    {this.state.carInfo.engine}
                                </div>
                            </div>
                        </div>
                        <div className="speed-data-driver-info">
                            <div className="speed-data-driver-info-bio">
                                <div className='speed-data-driver-name'>{this.state.carInfo.driverName}</div>
                                <div className='speed-data-driver-hometown'>
                                    <Icon icon="map-marker"/>&nbsp;{this.state.carInfo.hometown}
                                </div>
                                <div className='speed-data-driver-team'>
                                    <Icon icon="people"/>&nbsp;{this.state.carInfo.team}
                                </div>
                            </div>
                            <div className="speed-data-driver-info-other">
                                <div className="speed-data-driver-info-other-col">
                                    {/*{this.props.carData.distanceFromStart} [{this.props.carData.lap}]*/}
                                    <div>
                                        <Icon icon="id-number"/>
                                    </div>
                                    <div
                                        className="speed-data-driver-info-other-licence">{this.state.carInfo.license}</div>
                                </div>
                                <div className="speed-data-driver-info-other-col">
                                    <div>
                                        <Icon icon="drive-time"/>
                                    </div>
                                    <div
                                        className="speed-data-driver-info-other-competitor-id">{this.state.carInfo.uid}</div>
                                </div>
                                <div className="speed-data-driver-info-other-col">
                                    <div>
                                        <Icon icon="badge"/>
                                    </div>
                                    <div className="speed-data-driver-info-other-rank">#{this.props.rank}</div>
                                </div>
                            </div>
                        </div>
                    </div>
                    <div className="speed-data-lap-sections">
                        <Line data={{
                            labels: sortedLapNumbers,
                            datasets: [
                                {
                                    borderColor: "black",
                                    backgroundColor: "transparent",
                                    borderWidth: 1,
                                    data: sortedLaps,
                                    pointRadius: 2,
                                    pointBackgroundColor: "black",
                                    datalabels: {
                                        display: false
                                    }
                                }
                            ]
                        }}
                              options={{
                                  responsive: true,
                                  maintainAspectRatio: false,
                                  legend: {
                                      display: false
                                  },
                                  elements: {
                                      line: {
                                          tension: 0, // disables bezier curves
                                      }
                                  },
                                  animation: {
                                      duration: 0, // general animation time
                                  },
                                  hover: {
                                      animationDuration: 0, // duration of animations when hovering an item
                                  },
                                  responsiveAnimationDuration: 0, // animation duration after a resize
                                  scales: {
                                      yAxes: [{
                                          display: false,
                                          ticks: {
                                              display: false,
                                              beginAtZero: true
                                          },
                                          gridLines: {
                                              display: false,
                                          },
                                          scaleLabel: {
                                              display: false
                                          }
                                      }],
                                      xAxes: [{
                                          display: false,
                                          gridLines: {
                                              display: false,
                                          },
                                      }]
                                  },
                              }}/>
                        {/*<div className="speed-data-lap-sections-title">*/}
                        {/*Last Lap Section Speeds*/}
                        {/*</div>*/}
                        {/*<div className="speed-data-lap-sections-info">*/}
                        {/*{this.state.x.map((i, index) => {*/}
                        {/*return (<div className="speed-data-lap-section speed-data-lap-section-1">*/}
                        {/*<div className="speed-data-lap-section-label-wrapper">*/}
                        {/*<span className="speed-data-lap-section-label">{index + 1}</span>*/}
                        {/*</div>*/}
                        {/*<div className="speed-data-lap-section-time">*/}
                        {/*{Math.floor(Math.random() * 500)}*/}
                        {/*</div>*/}
                        {/*</div>)*/}
                        {/*})}*/}

                        {/*</div>*/}
                    </div>
                </div>
            </Card>
        );
    }
}

SpeedDataComponent.propTypes = {
    carNumber: PropTypes.oneOfType([PropTypes.string, PropTypes.number])
};